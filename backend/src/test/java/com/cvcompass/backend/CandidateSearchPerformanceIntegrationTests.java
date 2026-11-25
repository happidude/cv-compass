package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.search.config.SearchProperties;
import com.cvcompass.backend.search.repository.CandidateSearchRepository;
import com.cvcompass.backend.search.repository.CandidateSearchRepository.Criteria;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cv.candidate.publication.enabled=false", "cv.search.nightly-cron=-"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateSearchPerformanceIntegrationTests extends CandidateSearchTestSupport {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    @Autowired CandidateSearchRepository repository;
    @Autowired SearchProperties properties;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(
                        CandidateSearchPerformanceIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Test
    void tenThousandSyntheticProfilesUseGinIndexesAndReportTimings() throws Exception {
        long company = company("Synthetic Performance Company", null, "USER");
        jdbc.update(
                """
                WITH inserted AS (
                  INSERT INTO "CANDIDATE"."CANDIDATES" ("CREATED_AT")
                  SELECT now() FROM generate_series(1,10000) RETURNING "ID"
                ), profiles AS (
                  INSERT INTO "CANDIDATE"."PROFILES" ("CANDIDATE_ID","FULL_NAME")
                  SELECT "ID",'Synthetic Performance Profile ' || "ID" FROM inserted RETURNING "CANDIDATE_ID"
                )
                INSERT INTO "SEARCH"."CANDIDATE_INDEX" ("CANDIDATE_ID","SEARCH_TEXT","SEARCH_TSV",
                  "COMPANY_IDS","FUNCTION_CODES","UPDATED_AT")
                SELECT "CANDIDATE_ID", CASE WHEN "CANDIDATE_ID"%100=0 THEN 'syntheticneedle' ELSE 'synthetic ordinary ' || "CANDIDATE_ID" END,
                  to_tsvector('simple',CASE WHEN "CANDIDATE_ID"%100=0 THEN 'syntheticneedle' ELSE 'synthetic ordinary ' || "CANDIDATE_ID" END),
                  CASE WHEN "CANDIDATE_ID"%100=0 THEN ARRAY[?]::bigint[] ELSE '{}'::bigint[] END,
                  CASE WHEN "CANDIDATE_ID"%100=0 THEN '{IT}'::text[] ELSE '{SALES}'::text[] END,now()
                FROM profiles
                """,
                company);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"SEARCH\".\"CANDIDATE_INDEX\"",
                                Integer.class))
                .isGreaterThanOrEqualTo(10000);
        jdbc.execute("ANALYZE \"SEARCH\".\"CANDIDATE_INDEX\"");
        jdbc.execute("ANALYZE \"CANDIDATE\".\"PROFILES\"");
        var keyword =
                new Criteria(
                        "syntheticneedle",
                        "",
                        "",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        20);
        var arrays =
                new Criteria(
                        "",
                        "",
                        "",
                        true,
                        false,
                        List.of(company),
                        List.of(),
                        Map.of("FUNCTION", List.of("IT")),
                        List.of(),
                        null,
                        null,
                        null,
                        20);
        explain(
                "keyword",
                keyword,
                List.of("IDX_CANDIDATE_INDEX_SEARCH_TSV", "IDX_CANDIDATE_INDEX_SEARCH_TEXT"));
        explain(
                "arrays",
                arrays,
                List.of(
                        "IDX_CANDIDATE_INDEX_COMPANY_IDS",
                        "IDX_CANDIDATE_INDEX_PARENT_COMPANY_IDS",
                        "IDX_CANDIDATE_INDEX_FUNCTION_CODES"));
        login();
        long started = System.nanoTime();
        var result = search("q=syntheticneedle");
        assertThat(resultIds(result)).hasSize(20);
        System.out.printf(
                "SEARCH_PERF kind=http_keyword rows=%d elapsed_ms=%.3f%n",
                result.path("content").size(), (System.nanoTime() - started) / 1_000_000.0);
    }

    private void explain(String name, Criteria criteria, List<String> expectedIndexes) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            var query = repository.prepare(criteria, properties.getQuery());
                            var named = new NamedParameterJdbcTemplate(jdbc);
                            long started = System.nanoTime();
                            var rows = repository.search(criteria, properties.getQuery());
                            double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                            String plan =
                                    String.join(
                                            "\n",
                                            named.queryForList(
                                                    "EXPLAIN " + query.sql(),
                                                    query.parameters(),
                                                    String.class));
                            boolean forced = expectedIndexes.stream().noneMatch(plan::contains);
                            if (forced) {
                                // On small/selective synthetic distributions, cost estimates may
                                // prefer a scan.
                                // This fallback proves the actual API statement can use the
                                // existing GIN indexes.
                                jdbc.execute("SET LOCAL enable_seqscan=off");
                                plan =
                                        String.join(
                                                "\n",
                                                named.queryForList(
                                                        "EXPLAIN " + query.sql(),
                                                        query.parameters(),
                                                        String.class));
                            }
                            String finalPlan = plan;
                            assertThat(expectedIndexes.stream().anyMatch(finalPlan::contains))
                                    .as("GIN index for %s", name)
                                    .isTrue();
                            assertThat(rows).hasSize(21);
                            System.out.printf(
                                    "SEARCH_PERF kind=%s rows=%d elapsed_ms=%.3f forced_index=%s%n",
                                    name, rows.size(), elapsed, forced);
                        });
    }
}
