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
        long company = company("Syntheticneedle", null, "USER");
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
                SELECT "CANDIDATE_ID", CASE WHEN "CANDIDATE_ID"%100=0 THEN 'syntheticneedle' ELSE 'ordinary profile ' || "CANDIDATE_ID" END,
                  to_tsvector('simple',CASE WHEN "CANDIDATE_ID"%100=0 THEN 'syntheticneedle' ELSE 'ordinary profile ' || "CANDIDATE_ID" END),
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
                        Map.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        20);
        explain(
                "keyword",
                keyword,
                "IDX_CANDIDATE_INDEX_SEARCH_TSV",
                "IDX_CANDIDATE_INDEX_SEARCH_TEXT");
        explain(
                "arrays",
                arrays,
                "IDX_CANDIDATE_INDEX_COMPANY_IDS",
                "IDX_CANDIDATE_INDEX_PARENT_COMPANY_IDS");
        var companyByName =
                new Criteria(
                        "",
                        "syntheticneedle",
                        "",
                        true,
                        false,
                        repository.resolve("COMPANY", "syntheticneedle", properties.getQuery()),
                        List.of(),
                        Map.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        20);
        assertThat(companyByName.companyIds()).containsExactly(company);
        explain("company_name", companyByName, "IDX_CANDIDATE_INDEX_SEARCH_TEXT");
        login();
        long started = System.nanoTime();
        var result = search("q=syntheticneedle");
        assertThat(resultIds(result)).hasSize(20);
        System.out.printf(
                "SEARCH_PERF kind=http_keyword rows=%d elapsed_ms=%.3f%n",
                result.path("content").size(), (System.nanoTime() - started) / 1_000_000.0);
        started = System.nanoTime();
        var byCompanyId = search("companyId=" + company);
        assertThat(resultIds(byCompanyId)).hasSize(20);
        assertThat(resultIds(byCompanyId)).containsExactlyElementsOf(resultIds(result));
        System.out.printf(
                "SEARCH_PERF kind=http_arrays rows=%d elapsed_ms=%.3f%n",
                byCompanyId.path("content").size(), (System.nanoTime() - started) / 1_000_000.0);
    }

    private void explain(String name, Criteria criteria, String... requiredIndexes) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            var query = repository.prepare(criteria, properties.getQuery());
                            var named = new NamedParameterJdbcTemplate(jdbc);
                            long started = System.nanoTime();
                            var rows = repository.search(criteria, properties.getQuery());
                            double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                            if (!criteria.q().isEmpty()) {
                                assertThat(
                                                jdbc.queryForObject(
                                                        """
                                        SELECT count(*) FROM "SEARCH"."CANDIDATE_INDEX"
                                        WHERE "SEARCH_TSV" @@ plainto_tsquery('simple',?) OR ? <% "SEARCH_TEXT"
                                        """,
                                                        Integer.class, criteria.q(), criteria.q()))
                                        .as(
                                                "keyword fixture must match only one percent of profiles")
                                        .isEqualTo(100);
                            }
                            String plan =
                                    String.join(
                                            "\n",
                                            named.queryForList(
                                                    "EXPLAIN " + query.sql(),
                                                    query.parameters(),
                                                    String.class));
                            boolean forced = !usesRequiredIndex(plan, requiredIndexes);
                            if (forced) {
                                // On small/selective synthetic distributions, cost estimates may
                                // prefer a scan.
                                // This fallback proves the actual API statement can use the
                                // existing GIN indexes.
                                jdbc.execute("SET LOCAL enable_seqscan=off");
                                // At 10k rows the planner may also merge-join both primary keys;
                                // disabling plain index scans leaves only bitmap scans, which
                                // must come from the GIN index under test.
                                jdbc.execute("SET LOCAL enable_indexscan=off");
                                plan =
                                        String.join(
                                                "\n",
                                                named.queryForList(
                                                        "EXPLAIN " + query.sql(),
                                                        query.parameters(),
                                                        String.class));
                            }
                            assertThat(plan)
                                    .as("GIN index for %s", name)
                                    .containsAnyOf(requiredIndexes);
                            assertThat(rows).hasSize(21);
                            System.out.printf(
                                    "SEARCH_PERF kind=%s rows=%d elapsed_ms=%.3f forced_index=%s%n",
                                    name, rows.size(), elapsed, forced);
                        });
    }

    private boolean usesRequiredIndex(String plan, String[] requiredIndexes) {
        for (String index : requiredIndexes) {
            if (plan.contains(index)) return true;
        }
        return false;
    }
}
