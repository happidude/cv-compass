package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.candidate.events.CandidateChanged;
import com.cvcompass.backend.candidate.service.CandidateQueryService;
import com.cvcompass.backend.candidate.service.CandidateService;
import com.cvcompass.backend.catalog.Fold;
import com.cvcompass.backend.catalog.api.CatalogLookup;
import com.cvcompass.backend.search.config.SearchNightlyJob;
import com.cvcompass.backend.search.config.SearchProperties;
import com.cvcompass.backend.search.service.SearchIndexer;
import com.cvcompass.backend.search.service.SearchRebuildService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cv.candidate.publication.enabled=false",
            "cv.search.nightly-cron=-",
            "cv.search.rebuild-batch-size=2"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchIndexIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CandidateService candidates;
    @Autowired CandidateQueryService queries;
    @MockitoSpyBean CatalogLinks links;
    @Autowired CatalogLookup catalog;
    @Autowired SearchProperties properties;
    @Autowired SearchIndexer indexer;
    @Autowired SearchRebuildService rebuild;
    @Autowired SearchNightlyJob nightly;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(SearchIndexIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @ParameterizedTest
    @CsvSource({
        "2020-01-01,YEAR,2022-01-01,YEAR,36",
        "2020-01-01,YEAR,2020-01-01,YEAR,12",
        "2020-01-01,YEAR,2020-03-01,MONTH,3",
        "2020-12-01,MONTH,2021-01-01,YEAR,13",
        "2024-01-17,MONTH,2024-03-09,MONTH,3",
        "2024-01-20,DAY,2024-02-19,DAY,1"
    })
    void indexAndProfileUseExactlyTheHrTotalForPartialDates(
            String start, String startPrecision, String end, String endPrecision, long expected) {
        long id = candidate();
        experienceRow(id, start, startPrecision, end, endPrecision);
        indexer.rebuild(id);
        assertTotals(id, expected);
    }

    @Test
    void indexAndProfileUseTheHrUnionForOverlappingAndDuplicateRows() {
        long id = candidate();
        experienceRow(id, "2024-01-01", "MONTH", "2024-03-01", "MONTH");
        experienceRow(id, "2024-02-01", "MONTH", "2024-05-01", "MONTH");
        experienceRow(id, "2024-01-01", "MONTH", "2024-03-01", "MONTH");
        indexer.rebuild(id);
        assertTotals(id, 5);
    }

    @Test
    void ongoingUsesItsOwnImportDateAndHrRowsUseTheNewestImportInUtc() {
        long id = candidate();
        long source = document(id, "2024-04-01T00:30:00+07:00");
        document(id, "2025-12-31T00:00:00Z");
        experienceRow(id, "2024-01-01", "MONTH", null, null, true, source, true, null);
        indexer.rebuild(id);
        assertTotals(id, 3);
        assertThat(queries.detail(id).experienceSummary().ongoingReferenceDates())
                .containsExactly("2024-03-31");

        experienceRow(id, "2024-01-01", "MONTH", null, null, true, null, true, null);
        indexer.rebuild(id);
        assertTotals(id, 24);
        assertThat(queries.detail(id).experienceSummary().ongoingReferenceDates())
                .containsExactly("2024-03-31", "2025-12-31");
    }

    @Test
    void closedPeriodsAreCappedAtTheImportDateAndOngoingWithoutAnImportIsSkipped() {
        long capped = candidate();
        long source = document(capped, "2024-04-15T00:00:00Z");
        experienceRow(
                capped, "2024-01-01", "MONTH", "2024-12-01", "MONTH", false, source, true, null);
        indexer.rebuild(capped);
        assertTotals(capped, 3);

        long noReference = candidate();
        experienceRow(noReference, "2024-01-01", "MONTH", null, null, true, null, true, null);
        indexer.rebuild(noReference);
        assertTotals(noReference, 0);
    }

    @Test
    void indexAndProfileRespectHrExclusionsAndSkipUnresolvedOrDeletedRows() {
        long id = candidate();
        var counted = experienceRow(id, "2024-01-01", "MONTH", "2024-03-01", "MONTH");
        experienceRow(id, "2000-01-01", "YEAR", "2030-01-01", "YEAR", false, null, false, null);
        for (String state : List.of("INVALID", "AMBIGUOUS"))
            experienceRow(id, "2000-01-01", "YEAR", "2030-01-01", "YEAR", false, null, true, state);
        experienceRow(id, "2024-03-20", "DAY", "2024-03-10", "DAY");
        experienceRow(id, null, null, "2024-12-01", "MONTH");
        experienceRow(id, "2024-01-01", "MONTH", null, null);
        var deleted = experienceRow(id, "2000-01-01", "YEAR", "2030-01-01", "YEAR");
        jdbc.update(
                "UPDATE \"CANDIDATE\".\"EXPERIENCES\" SET \"DELETED\"=true WHERE \"ID\"=?",
                deleted);
        indexer.rebuild(id);
        assertTotals(id, 3);
        assertThat(queries.detail(id).experienceSummary().countedIds()).containsExactly(counted);
    }

    @Test
    void lookupFiltersMixedEntryIdsByKindAndPreservesCompanyAndTitleFields() {
        long parent = company(null, "SEED");
        long company = company(parent, "AI");
        long title = title("USER");
        var ids = List.of(parent, company, title);
        var companies = catalog.companies(ids);
        assertThat(companies).containsOnlyKeys(parent, company);
        assertThat(companies.get(company))
                .isEqualTo(
                        new CatalogLookup.Company(
                                company,
                                jdbc.queryForObject(
                                        "SELECT \"NAME\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"ID\"=?",
                                        String.class,
                                        company),
                                parent,
                                companies.get(parent).name(),
                                "LOGISTICS",
                                "AI"));
        assertThat(companies.get(parent).parentId()).isNull();
        assertThat(companies.get(parent).parentName()).isNull();
        var titles = catalog.titles(ids);
        assertThat(titles).containsOnlyKeys(title);
        assertThat(titles.get(title))
                .isEqualTo(
                        new CatalogLookup.Title(
                                title,
                                jdbc.queryForObject(
                                        "SELECT \"NAME\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"ID\"=?",
                                        String.class,
                                        title),
                                List.of("IT", "HR"),
                                "MANAGER",
                                "COMPANY",
                                "USER"));
        assertThat(catalog.companies(List.of(title))).isEmpty();
        assertThat(catalog.titles(List.of(company))).isEmpty();
        assertThat(catalog.companies(List.of())).isEmpty();
        assertThat(catalog.titles(List.of())).isEmpty();
    }

    @Test
    void indexesCatalogNamesParentCodesAndSortedDistinctArraysFromActiveLinks() {
        long id = candidate();
        var first =
                candidates.editExperience(
                        id, 0, null, experience(false), "SAVE", "synthetic-editor");
        var second =
                candidates.editExperience(
                        id, first.version(), null, experience(false), "SAVE", "synthetic-editor");
        long parent = company(null, "SEED");
        long company = company(parent, "AI");
        long title = title("SEED");
        var normalization =
                new CatalogLinks.Normalization(
                        company,
                        title,
                        "REGION",
                        "USER",
                        "Synthetic canonical employer",
                        List.of("IT", "HR"),
                        List.of("LOGISTICS"),
                        "MANAGER");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            for (var row : second.experiences())
                                assertThat(links.normalize(id, row.id(), normalization)).isTrue();
                            events.publishEvent(new CandidateChanged(id));
                            assertThat(text(id))
                                    .contains(
                                            "synthetic nguyen (alias)",
                                            "synthetic raw title",
                                            "synthetic raw employer",
                                            "canonical title",
                                            "canonical company",
                                            "ha noi",
                                            "synthetic summary");
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT \"SEARCH_TSV\" @@ plainto_tsquery('simple','canonical title') FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                                                    Boolean.class,
                                                    id))
                                    .isTrue();
                        });
        assertThat(longs(id, "COMPANY_IDS")).containsExactly(company);
        assertThat(longs(id, "PARENT_COMPANY_IDS")).containsExactly(parent);
        assertThat(text(id))
                .contains(
                        Fold.fold(
                                jdbc.queryForObject(
                                        "SELECT \"NAME\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"KIND\"='COMPANY' AND \"ID\"=?",
                                        String.class,
                                        parent)));
        assertThat(longs(id, "TITLE_IDS")).containsExactly(title);
        assertThat(strings(id, "FUNCTION_CODES")).containsExactly("HR", "IT");
        assertThat(strings(id, "SENIORITY_CODES")).containsExactly("MANAGER");
        assertThat(strings(id, "INDUSTRY_CODES")).containsExactly("LOGISTICS");
        assertThat(strings(id, "SCOPE_CODES")).containsExactly("REGION");
        assertThat(ai(id)).isTrue();
        var metrics = links.profile(id).orElseThrow();
        assertThat(metrics.totalExperienceMonths()).isEqualTo(3);
        assertThat(metrics.locationProvince()).isEqualTo("Hà Nội");
        assertThat(metrics.currentCompanyId()).isEqualTo(company);
        assertThat(metrics.currentTitleId()).isEqualTo(title);

        // Two different IDs are sorted independently of experience UUID order.
        long earlierCompany = company(null, "USER");
        long earlierTitle = title("AI");
        var replacement =
                new CatalogLinks.Normalization(
                        earlierCompany,
                        earlierTitle,
                        "BUSINESS_UNIT",
                        "USER",
                        "Synthetic other company",
                        List.of("IT", "HR"),
                        List.of("LOGISTICS"),
                        "MANAGER");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                links.normalize(
                                        id, second.experiences().getFirst().id(), replacement));
        indexer.rebuild(id);
        assertThat(longs(id, "COMPANY_IDS")).containsExactly(company, earlierCompany);
        assertThat(longs(id, "TITLE_IDS")).containsExactly(title, earlierTitle);
        assertThat(strings(id, "SCOPE_CODES")).containsExactly("BUSINESS_UNIT", "REGION");
    }

    @Test
    void hrEditsRebuildTextMetricsAndRemoveInvalidatedOrDeletedLinks() {
        long id = candidate();
        var initial =
                candidates.editExperience(
                        id, 0, null, experience(false), "SAVE", "synthetic-editor");
        UUID experienceId = initial.experiences().getFirst().id();
        long company = company(null, "SEED");
        long title = title("SEED");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                links.normalize(
                                        id,
                                        experienceId,
                                        new CatalogLinks.Normalization(
                                                company,
                                                title,
                                                "COMPANY",
                                                "AI",
                                                "Synthetic linked",
                                                List.of("HR", "IT"),
                                                List.of("LOGISTICS"),
                                                "MANAGER")));
        indexer.rebuild(id);
        assertThat(ai(id)).isTrue();
        var changed = experience(false);
        ((ObjectNode) changed.path("company")).put("raw", "Synthetic replacement employer");
        ((ObjectNode) changed.path("period").path("end")).put("month", 5);
        var edited =
                candidates.editExperience(
                        id, initial.version(), experienceId, changed, "SAVE", "synthetic-editor");
        assertThat(text(id))
                .contains("synthetic replacement employer")
                .doesNotContain("synthetic raw employer", "canonical company", "canonical title");
        assertThat(longs(id, "COMPANY_IDS")).isEmpty();
        assertThat(longs(id, "TITLE_IDS")).isEmpty();
        assertThat(strings(id, "FUNCTION_CODES")).isEmpty();
        assertThat(ai(id)).isFalse();
        assertThat(links.profile(id).orElseThrow().totalExperienceMonths()).isEqualTo(5);
        assertThat(links.profile(id).orElseThrow().currentCompanyId()).isNull();
        var basic =
                candidates.basic(
                        id,
                        edited.version(),
                        json.readTree(
                                "{\"fullName\":\"Synthetic Changed Alias\",\"location\":\"Hanoi / HCMC\",\"summary\":\"Synthetic updated summary\"}"),
                        false,
                        "synthetic-editor");
        assertThat(text(id))
                .contains("synthetic changed alias", "synthetic updated summary")
                .doesNotContain("synthetic nguyen", "synthetic summary");
        assertThat(links.profile(id).orElseThrow().locationProvince()).isNull();
        candidates.editExperience(
                id, basic.version(), experienceId, null, "DELETE", "synthetic-editor");
        assertThat(text(id)).doesNotContain("replacement employer", "synthetic raw title");
        assertThat(links.profile(id).orElseThrow().totalExperienceMonths()).isZero();
    }

    @Test
    void missingProfileDeletesAnExistingSnapshot() {
        long id = candidate();
        indexer.rebuild(id);
        assertThat(indexCount(id)).isEqualTo(1);
        jdbc.update("DELETE FROM \"CANDIDATE\".\"PROFILES\" WHERE \"CANDIDATE_ID\"=?", id);
        indexer.rebuild(id);
        assertThat(indexCount(id)).isZero();
    }

    @Test
    void titleAndExperienceOriginsIndependentlyMarkAiNormalization() {
        long id = candidate();
        var profile =
                candidates.editExperience(
                        id, 0, null, experience(false), "SAVE", "synthetic-editor");
        UUID experienceId = profile.experiences().getFirst().id();
        long company = company(null, "USER");
        long title = title("AI");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                links.normalize(
                                        id,
                                        experienceId,
                                        new CatalogLinks.Normalization(
                                                company,
                                                title,
                                                null,
                                                "USER",
                                                "Synthetic linked",
                                                List.of("IT", "HR"),
                                                List.of("LOGISTICS"),
                                                "MANAGER")));
        indexer.rebuild(id);
        assertThat(ai(id)).isTrue();
        assertThat(strings(id, "SCOPE_CODES")).containsExactly("COMPANY");
        jdbc.update("UPDATE \"CATALOG\".\"ENTRIES\" SET \"ORIGIN\"='USER' WHERE \"ID\"=?", title);
        indexer.rebuild(id);
        assertThat(ai(id)).isFalse();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                links.normalize(
                                        id,
                                        experienceId,
                                        new CatalogLinks.Normalization(
                                                company,
                                                title,
                                                null,
                                                "AI",
                                                "Synthetic linked",
                                                List.of("IT", "HR"),
                                                List.of("LOGISTICS"),
                                                "MANAGER")));
        indexer.rebuild(id);
        assertThat(ai(id)).isTrue();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                links.normalize(
                                        id,
                                        experienceId,
                                        new CatalogLinks.Normalization(
                                                null, null, null, "AI", null, List.of(), List.of(),
                                                null)));
        indexer.rebuild(id);
        assertThat(ai(id)).isTrue();
        assertThat(longs(id, "COMPANY_IDS")).isEmpty();
        assertThat(longs(id, "TITLE_IDS")).isEmpty();
    }

    @Test
    void nightlyRepairsAllCandidatesUsingImportDates() {
        long ongoing = candidate();
        document(ongoing, "2026-11-22T00:00:00Z");
        candidates.editExperience(ongoing, 0, null, experience(true), "SAVE", "synthetic-editor");
        long ended = candidate();
        candidates.editExperience(ended, 0, null, experience(false), "SAVE", "synthetic-editor");
        long deleted = candidate();
        var row =
                candidates.editExperience(
                        deleted, 0, null, experience(true), "SAVE", "synthetic-editor");
        candidates.editExperience(
                deleted,
                row.version(),
                row.experiences().getFirst().id(),
                null,
                "DELETE",
                "synthetic-editor");
        String endedVersion = indexVersion(ended);
        String deletedVersion = indexVersion(deleted);
        assertTotals(ongoing, 10);
        String ongoingVersion = indexVersion(ongoing);
        // A stale cached value must be repaired without advancing past the import date.
        jdbc.update(
                "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"TOTAL_EXPERIENCE_MONTHS\"=999 WHERE \"CANDIDATE_ID\"=?",
                ongoing);
        jdbc.update(
                "UPDATE \"SEARCH\".\"CANDIDATE_INDEX\" SET \"TOTAL_EXPERIENCE_MONTHS\"=999 WHERE \"CANDIDATE_ID\"=?",
                ongoing);
        nightly.rebuild();
        verify(links, atLeastOnce()).indexCandidates(anyLong(), anyLong(), eq(2));
        assertTotals(ongoing, 10);
        assertThat(indexVersion(ongoing)).isNotEqualTo(ongoingVersion);
        // Nightly rebuild also repairs ended and deleted-only profiles.
        assertThat(indexVersion(ended)).isNotEqualTo(endedVersion);
        assertThat(indexVersion(deleted)).isNotEqualTo(deletedVersion);
        assertTotals(ended, 3);
        assertTotals(deleted, 0);
    }

    @Test
    void adminEndpointRebuildsEveryCandidateAndRejectsOtherUsers() throws Exception {
        for (int n = 0; n < 401; n++) candidate();
        long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM \"CANDIDATE\".\"CANDIDATES\"", Long.class);
        long before =
                jdbc.queryForObject(
                        "SELECT count(*) FROM \"SEARCH\".\"CANDIDATE_INDEX\"", Long.class);
        var unauthenticated =
                client.send(
                        HttpRequest.newBuilder(endpoint())
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode()).isIn(401, 403);
        var denied = post(session("USER"));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(denied.body()))
                .isEqualTo(
                        json.readTree(
                                "{\"code\":\"ACCESS_DENIED\",\"message\":\"Request denied\",\"correlationId\":null,\"details\":[]}"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"SEARCH\".\"CANDIDATE_INDEX\"", Long.class))
                .isEqualTo(before);
        var result = post(session("ADMIN"));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json.readTree(result.body()).path("rebuilt").asLong()).isEqualTo(total);
        assertThat(json.readTree(result.body()).path("failed").asLong()).isZero();
        assertThat(properties.getRebuildBatchSize()).isEqualTo(2);
        verify(links, times(Math.toIntExact((total + 1) / 2 + 1)))
                .indexCandidates(anyLong(), anyLong(), eq(2));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"SEARCH\".\"CANDIDATE_INDEX\"", Long.class))
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CANDIDATE\".\"PROFILES\"", Long.class));
    }

    @Test
    void oneFailingCandidateDoesNotBlockTheRestOfTheRebuild() {
        long bad = candidate();
        long good = candidate();
        poisonTrigger();
        jdbc.update(
                "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"FULL_NAME\"='Synthetic poison marker' WHERE \"CANDIDATE_ID\"=?",
                bad);
        try {
            var result = rebuild.rebuildAll();
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.rebuilt()).isGreaterThanOrEqualTo(1);
        } finally {
            dropPoisonTrigger();
        }
        assertThat(indexCount(bad)).isZero();
        assertThat(indexCount(good)).isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INDEX", "METRICS"})
    void indexFailureRollsBackItsSavepointWhileHrEditCommitsAndRebuildRepairsIt(
            String failureStage) {
        assertThat(transactions)
                .isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
        long id = candidate();
        if (failureStage.equals("INDEX")) poisonTrigger();
        else {
            jdbc.execute(
                    "CREATE OR REPLACE FUNCTION \"CANDIDATE\".synthetic_metrics_poison() RETURNS trigger AS $$ BEGIN IF NEW.\"FULL_NAME\" LIKE '%Synthetic poison marker%' THEN RAISE EXCEPTION 'synthetic poison'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
            jdbc.execute(
                    "CREATE TRIGGER synthetic_metrics_poison BEFORE UPDATE OF \"TOTAL_EXPERIENCE_MONTHS\" ON \"CANDIDATE\".\"PROFILES\" FOR EACH ROW EXECUTE FUNCTION \"CANDIDATE\".synthetic_metrics_poison()");
        }
        Throwable failure = null;
        try {
            try {
                var changed =
                        candidates.basic(
                                id,
                                0,
                                json.readTree("{\"fullName\":\"Synthetic poison marker\"}"),
                                false,
                                "synthetic-editor");
                assertThat(changed.basic().path("fullName").asString())
                        .isEqualTo("Synthetic poison marker");
                assertThat(queries.detail(id).version()).isEqualTo(1);
                assertThat(indexCount(id)).isZero();
                // The metrics write precedes the poisoned index write and must roll back too.
                assertThat(links.profile(id).orElseThrow().totalExperienceMonths()).isNull();
            } finally {
                dropPoisonTrigger();
                jdbc.execute(
                        "DROP TRIGGER IF EXISTS synthetic_metrics_poison ON \"CANDIDATE\".\"PROFILES\"");
            }
            assertThat(rebuild.rebuildAll().failed()).isZero();
            assertThat(indexCount(id)).isEqualTo(1);
            assertThat(text(id)).contains("synthetic poison marker");
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            // The class shares one database: drop the marker so later poisoned rebuilds count
            // only their own candidate, even when an assertion fails.
            try {
                jdbc.update(
                        "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"FULL_NAME\"='Synthetic repaired profile' WHERE \"CANDIDATE_ID\"=?",
                        id);
                indexer.rebuild(id);
            } catch (RuntimeException | Error cleanupError) {
                if (failure != null) failure.addSuppressed(cleanupError);
                else throw cleanupError;
            }
        }
    }

    /** Makes the index write fail for synthetic poisoned rows, like a real per-candidate fault. */
    private void poisonTrigger() {
        jdbc.execute(
                "CREATE OR REPLACE FUNCTION \"SEARCH\".synthetic_poison() RETURNS trigger AS $$ BEGIN IF NEW.\"SEARCH_TEXT\" LIKE '%synthetic poison marker%' THEN RAISE EXCEPTION 'synthetic poison'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute(
                "CREATE OR REPLACE TRIGGER synthetic_poison BEFORE INSERT OR UPDATE ON \"SEARCH\".\"CANDIDATE_INDEX\" FOR EACH ROW EXECUTE FUNCTION \"SEARCH\".synthetic_poison()");
    }

    private void dropPoisonTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS synthetic_poison ON \"SEARCH\".\"CANDIDATE_INDEX\"");
    }

    private long candidate() {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO \"CANDIDATE\".\"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"",
                        Long.class);
        String basic =
                "{\"fullName\":\"Synthetic Nguyễn (Alias)\",\"location\":\"Hà Nội\",\"summary\":\"Synthetic summary\"}";
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"PROFILES\" (\"CANDIDATE_ID\",\"BASIC\",\"FULL_NAME\",\"LOCATION\",\"SUMMARY\") VALUES (?,?::jsonb,?,?,?)",
                id,
                basic,
                "Synthetic Nguyễn (Alias)",
                "Hà Nội",
                "Synthetic summary");
        return id;
    }

    private void assertTotals(long id, long expected) {
        long hrTotal = queries.detail(id).experienceSummary().totalMonths();
        assertThat(hrTotal).isEqualTo(expected);
        assertThat(links.profile(id).orElseThrow().totalExperienceMonths().longValue())
                .isEqualTo(hrTotal);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"TOTAL_EXPERIENCE_MONTHS\" FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                                Long.class,
                                id))
                .isEqualTo(hrTotal);
    }

    private long document(long candidateId, String importedAt) {
        return jdbc.queryForObject(
                """
                INSERT INTO "INGESTION"."DOCUMENTS"
                  ("CANDIDATE_ID","ORIGINAL_FILENAME","STORAGE_PATH","FILE_SIZE","MIME_TYPE",
                   "SHA256","STATUS","CREATED_AT")
                VALUES (?,'synthetic.pdf','synthetic.pdf',1,'application/pdf',?,'COMPLETED',?)
                RETURNING "ID"
                """,
                Long.class,
                candidateId,
                "0".repeat(64),
                OffsetDateTime.parse(importedAt));
    }

    private UUID experienceRow(
            long id, String start, String startPrecision, String end, String endPrecision) {
        return experienceRow(id, start, startPrecision, end, endPrecision, false, null, true, null);
    }

    private UUID experienceRow(
            long id,
            String start,
            String startPrecision,
            String end,
            String endPrecision,
            boolean ongoing,
            Long documentId,
            boolean countInTotal,
            String state) {
        var key = UUID.randomUUID();
        var data = json.createObjectNode().put("countInTotal", countInTotal);
        data.putObject("period").put("state", state);
        jdbc.update(
                """
                INSERT INTO "CANDIDATE"."EXPERIENCES"
                  ("ID","CANDIDATE_ID","START_DATE","START_PRECISION","END_DATE","END_PRECISION",
                   "ONGOING_AS_STATED","DOCUMENT_ID","DATA","STATE","ORIGIN")
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,'UNVERIFIED','MAPPING')
                """,
                key,
                id,
                start == null ? null : LocalDate.parse(start),
                startPrecision,
                end == null ? null : LocalDate.parse(end),
                endPrecision,
                ongoing,
                documentId,
                data.toString());
        return key;
    }

    private ObjectNode experience(boolean ongoing) {
        var data =
                (ObjectNode)
                        json.readTree(
                                """
                {"company":{"raw":"Synthetic raw employer"},"title":{"raw":"Synthetic raw title"},
                 "period":{"start":{"year":2026,"month":1,"precision":"MONTH"},
                 "end":{"year":2026,"month":3,"precision":"MONTH"},"ongoingAsStated":false}}
                """);
        if (ongoing) ((ObjectNode) data.path("period")).put("ongoingAsStated", true).putNull("end");
        return data;
    }

    private long company(Long parent, String origin) {
        String name = "Synthetic canonical company " + UUID.randomUUID();
        return jdbc.queryForObject(
                "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"PARENT_ID\",\"INDUSTRY_CODE\",\"ORIGIN\") VALUES ('COMPANY',?,?,?,'LOGISTICS',?) RETURNING \"ID\"",
                Long.class,
                name,
                name,
                parent,
                origin);
    }

    private long title(String origin) {
        String name = "Synthetic canonical title " + UUID.randomUUID();
        return jdbc.queryForObject(
                "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"SCOPE_CODE\",\"ORIGIN\") VALUES ('TITLE',?,?,'{IT,HR}','MANAGER','COMPANY',?) RETURNING \"ID\"",
                Long.class,
                name,
                name,
                origin);
    }

    private String text(long id) {
        return jdbc.queryForObject(
                "SELECT \"SEARCH_TEXT\" FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                String.class,
                id);
    }

    private List<Long> longs(long id, String column) {
        return jdbc.queryForList(
                "SELECT unnest(\""
                        + column
                        + "\") FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                Long.class,
                id);
    }

    private List<String> strings(long id, String column) {
        return jdbc.queryForList(
                "SELECT unnest(\""
                        + column
                        + "\") FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                String.class,
                id);
    }

    private boolean ai(long id) {
        return jdbc.queryForObject(
                "SELECT \"HAS_AI_NORMALIZATION\" FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                Boolean.class,
                id);
    }

    private int indexCount(long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                Integer.class,
                id);
    }

    private String indexVersion(long id) {
        return jdbc.queryForObject(
                "SELECT xmin::text FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                String.class,
                id);
    }

    private TestUserSession session(String role) throws Exception {
        jdbc.update(
                "INSERT INTO \"IDENTITY\".\"USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-http',?,?) ON CONFLICT (\"USERNAME\") DO UPDATE SET \"ROLE\"=EXCLUDED.\"ROLE\",\"PASSWORD_HASH\"=EXCLUDED.\"PASSWORD_HASH\"",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
                        .encode("synthetic-http-test-only"),
                role);
        return TestUserSession.login(client, port, jdbc, json);
    }

    private URI endpoint() {
        return URI.create("http://localhost:" + port + "/api/admin/search/rebuild");
    }

    private HttpResponse<String> post(TestUserSession session) throws Exception {
        return client.send(
                session.request(endpoint()).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
