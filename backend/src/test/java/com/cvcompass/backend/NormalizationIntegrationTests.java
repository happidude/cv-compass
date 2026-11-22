package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.candidate.events.CandidateChanged;
import com.cvcompass.backend.candidate.service.CandidateService;
import com.cvcompass.backend.catalog.Fold;
import com.cvcompass.backend.catalog.config.NormalizationClock;
import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.config.NormalizationWorkerAuthenticationFilter;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Claim;
import com.cvcompass.backend.catalog.exception.NormalizationFailure;
import com.cvcompass.backend.catalog.repository.NormalizationTaskRepository;
import com.cvcompass.backend.catalog.service.NormalizationLinkService;
import com.cvcompass.backend.catalog.service.NormalizationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        properties = {
            "cv.catalog.normalization.enabled=true",
            "cv.catalog.normalization.backfill-delay=24h",
            "cv.search.rebuild.cron=-"
        })
@AutoConfigureMockMvc
@Import(NormalizationIntegrationTests.EventConfiguration.class)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NormalizationIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static final String TOKEN = "synthetic-normalization-" + "n".repeat(43);
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CandidateService candidates;
    @Autowired CatalogLinks links;
    @Autowired NormalizationService service;
    @Autowired NormalizationTaskRepository tasks;
    @Autowired NormalizationLinkService linker;
    @Autowired NormalizationProperties properties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired MockMvc mvc;
    @Autowired ChangeCounter changes;

    @MockitoBean(name = "normalizationClock")
    NormalizationClock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(NormalizationIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @BeforeEach
    void configureWorker() {
        changes.counts.clear();
        when(clock.clock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        properties.setEnabled(true);
        properties.setMaxBodyBytes(65536);
        var worker = new NormalizationProperties.Worker();
        worker.setCredentialSha256(Set.of(NormalizationWorkerAuthenticationFilter.hash(TOKEN)));
        properties.setWorkers(Map.of("synthetic-worker", worker));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventConfiguration {
        @Bean
        ChangeCounter changeCounter() {
            return new ChangeCounter();
        }
    }

    static class ChangeCounter {
        final Map<Long, AtomicInteger> counts = new ConcurrentHashMap<>();

        @EventListener
        public void changed(CandidateChanged event) {
            counts.computeIfAbsent(event.candidateId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
        }
    }

    @Test
    void disabledWorkersStillLinkAliasesAndEnqueueMissesInTheDatabase() {
        properties.setEnabled(false);
        long company = company("Synthetic disabled employer", "SEED");
        alias("COMPANY", "Synthetic disabled raw", company);
        long candidate = candidate();
        insertExperience(candidate, "Synthetic disabled raw", "Synthetic disabled title", null);
        events.publishEvent(new CandidateChanged(candidate));
        var row = links.experiences(candidate).getFirst();
        assertThat(row.companyId()).isEqualTo(company);
        assertThat(row.titleId()).isNull();
        assertThat(row.normalizationOrigin()).isNull();
        assertThat(countTasks()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"KIND\"='TITLE'",
                                String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void oneSubmitLinksAllPagesAndPublishesOneChangePerAffectedCandidate() {
        var affected = new java.util.ArrayList<Long>();
        for (int i = 0; i < 103; i++) {
            long id = candidate();
            insertExperience(id, "Synthetic batch company A", "Synthetic batch title A", null);
            insertExperience(id, "Synthetic batch company B", "Synthetic batch title B", null);
            affected.add(id);
        }
        long unrelated = candidate();
        insertExperience(unrelated, "Synthetic unrelated", null, null);
        long user = candidate();
        insertExperience(user, "Synthetic batch company A", "Synthetic batch title A", "USER");
        for (String suffix : List.of("A", "B")) {
            tasks.enqueue(
                    "COMPANY",
                    Fold.fold("Synthetic batch company " + suffix),
                    "Synthetic batch company " + suffix,
                    null);
            tasks.enqueue(
                    "TITLE",
                    Fold.fold("Synthetic batch title " + suffix),
                    "Synthetic batch title " + suffix,
                    null);
        }
        var claim = service.claim(50);
        var results =
                claim.items().stream()
                        .map(
                                item ->
                                        item.kind().equals("COMPANY")
                                                ? companyResult(
                                                        item.id(),
                                                        item.raw() + " canonical",
                                                        null,
                                                        "RETAIL")
                                                : titleResult(
                                                        item.id(), "[\"IT\"]", "STAFF", null, null))
                        .toList();
        assertThat(service.submit(claim.token(), results).accepted()).isEqualTo(4);
        assertThat(changes.counts.keySet()).containsExactlyInAnyOrderElementsOf(affected);
        for (long id : affected) {
            assertThat(changes.counts.get(id).get()).isEqualTo(1);
            assertThat(links.experiences(id))
                    .hasSize(2)
                    .allSatisfy(
                            row -> {
                                assertThat(row.companyId()).isNotNull();
                                assertThat(row.titleId()).isNotNull();
                                assertThat(row.normalizationOrigin()).isEqualTo("AI");
                            });
            assertThat(indexText(id))
                    .contains(
                            "synthetic batch company a canonical",
                            "synthetic batch company b canonical");
        }
        assertThat(links.experiences(user).getFirst().companyId()).isNull();
        assertThat(links.experiences(unrelated).getFirst().companyId()).isNull();
    }

    @Test
    void databaseConstraintFailureRejectsOnlyThatResultAndRollsBackItsEntryAndParent() {
        jdbc.execute(
                "ALTER TABLE \"CATALOG\".\"COMPANY_ALIASES\" ADD CONSTRAINT \"TEST_NORMALIZATION_ALIAS\" CHECK (\"ALIAS_FOLDED\" <> 'synthetic rejected alias')");
        for (String raw :
                List.of(
                        "Synthetic valid before",
                        "Synthetic rejected alias",
                        "Synthetic valid after"))
            tasks.enqueue("COMPANY", Fold.fold(raw), raw, null);
        var claim = service.claim(50);
        var results =
                claim.items().stream()
                        .map(
                                item ->
                                        companyResult(
                                                item.id(),
                                                item.raw() + " canonical",
                                                item.raw().contains("rejected")
                                                        ? "Synthetic rejected parent"
                                                        : null,
                                                "RETAIL"))
                        .toList();
        var reply = service.submit(claim.token(), results);
        assertThat(reply.accepted()).isEqualTo(2);
        assertThat(reply.rejected())
                .singleElement()
                .satisfies(rejection -> assertThat(rejection.code()).isEqualTo("INVALID_RESULT"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\" IN ('synthetic rejected alias canonical','synthetic rejected parent')",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CATALOG\".\"COMPANY_ALIASES\"",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"STATE\"='DONE'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATE\" || ':' || \"ERROR_CODE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"ALIAS_FOLDED\"='synthetic rejected alias'",
                                String.class))
                .isEqualTo("PENDING:INVALID_RESULT");
    }

    @Test
    void namesAndOtherLabelsUseTheDatabaseUnicodeWhitespaceClass() {
        String whitespace =
                jdbc.queryForObject(
                        "SELECT string_agg(chr(c), '') FROM unnest(ARRAY[9,10,11,12,13,32,133,160,5760,8192,8199,8239,12288]) c WHERE chr(c) !~ '[^[:space:]]'",
                        String.class);
        assertThat(whitespace).isNotEmpty();
        tasks.enqueue("COMPANY", "synthetic blank name", "Synthetic blank name", null);
        tasks.enqueue(
                "COMPANY",
                "synthetic blank industry label",
                "Synthetic blank industry label",
                null);
        tasks.enqueue(
                "TITLE", "synthetic blank function label", "Synthetic blank function label", null);
        var claim = service.claim(50);
        var results = new java.util.ArrayList<JsonNode>();
        for (var item : claim.items()) {
            if (item.kind().equals("TITLE"))
                results.add(titleResult(item.id(), "[\"OTHER\"]", "STAFF", null, whitespace));
            else {
                var result =
                        (ObjectNode)
                                companyResult(
                                        item.id(),
                                        item.raw().contains("name")
                                                ? whitespace
                                                : "Synthetic other company",
                                        null,
                                        item.raw().contains("name") ? "RETAIL" : "OTHER");
                ((ObjectNode) result.path("company")).put("proposedLabel", whitespace);
                results.add(result);
            }
        }
        var reply = service.submit(claim.token(), results);
        assertThat(reply.accepted()).isZero();
        assertThat(reply.rejected())
                .extracting(r -> r.code())
                .containsExactlyInAnyOrder(
                        "INVALID_RESULT", "PROPOSED_LABEL_REQUIRED", "PROPOSED_LABEL_REQUIRED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"STATE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\"",
                                String.class))
                .containsOnly("PENDING");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void replayAfterCommittedSubmitReturns409WithoutChanges() throws Exception {
        String raw = "Synthetic replay " + UUID.randomUUID();
        tasks.enqueue("COMPANY", Fold.fold(raw), raw, null);
        var claim = service.claim(1);
        long id = task(claim, "COMPANY");
        var result = companyResult(id, raw, null, "RETAIL");
        try {
            assertThat(service.submit(claim.token(), List.of(result)).accepted()).isEqualTo(1);
            var before = submitSnapshot(Fold.fold(raw), id);
            mvc.perform(
                            put("/internal/normalization/v1/results")
                                    .header("Authorization", "Bearer " + TOKEN)
                                    .contentType("application/json")
                                    .content(
                                            json.createObjectNode()
                                                    .put("token", claim.token().toString())
                                                    .set(
                                                            "results",
                                                            json.createArrayNode().add(result))
                                                    .toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LEASE_LOST"));
            assertThat(submitSnapshot(Fold.fold(raw), id)).isEqualTo(before);
        } finally {
            removeCommittedSubmit(Fold.fold(raw), id);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSubmitsOfTheSameLeaseCommitExactlyOnce() throws Exception {
        String raw = "Synthetic concurrent submit " + UUID.randomUUID();
        tasks.enqueue("COMPANY", Fold.fold(raw), raw, null);
        var claim = service.claim(1);
        long id = task(claim, "COMPANY");
        var ready = new CountDownLatch(2);
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var request =
                    json.createObjectNode()
                            .put("token", claim.token().toString())
                            .set(
                                    "results",
                                    json.createArrayNode()
                                            .add(companyResult(id, raw, null, "RETAIL")))
                            .toString();
            java.util.concurrent.Callable<Integer> submit =
                    () ->
                            tx.execute(
                                    status -> {
                                        // Establish both snapshots before either submit consumes
                                        // the lease.
                                        jdbc.queryForObject("SELECT 1", Integer.class);
                                        ready.countDown();
                                        try {
                                            if (!ready.await(10, TimeUnit.SECONDS))
                                                throw new AssertionError("Submit barrier timeout");
                                            int response =
                                                    mvc.perform(
                                                                    put("/internal/normalization/v1/results")
                                                                            .header(
                                                                                    "Authorization",
                                                                                    "Bearer "
                                                                                            + TOKEN)
                                                                            .contentType(
                                                                                    "application/json")
                                                                            .content(request))
                                                            .andReturn()
                                                            .getResponse()
                                                            .getStatus();
                                            if (response != 200) status.setRollbackOnly();
                                            return response;
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            throw new AssertionError(interrupted);
                                        } catch (Exception failure) {
                                            throw new AssertionError(failure);
                                        }
                                    });
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"=?",
                                    Integer.class,
                                    Fold.fold(raw)))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CATALOG\".\"COMPANY_ALIASES\" WHERE \"ALIAS_FOLDED\"=?",
                                    Integer.class,
                                    Fold.fold(raw)))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"STATE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"ID\"=?",
                                    String.class,
                                    id))
                    .isEqualTo("DONE");
        } finally {
            removeCommittedSubmit(Fold.fold(raw), id);
        }
    }

    private List<String> submitSnapshot(String folded, long task) {
        return List.of(
                jdbc.queryForObject(
                        "SELECT to_jsonb(c)::text FROM \"CATALOG\".\"COMPANIES\" c WHERE \"NAME_FOLDED\"=?",
                        String.class,
                        folded),
                jdbc.queryForObject(
                        "SELECT to_jsonb(a)::text FROM \"CATALOG\".\"COMPANY_ALIASES\" a WHERE \"ALIAS_FOLDED\"=?",
                        String.class,
                        folded),
                jdbc.queryForObject(
                        "SELECT to_jsonb(t)::text FROM \"CATALOG\".\"NORMALIZATION_TASKS\" t WHERE \"ID\"=?",
                        String.class,
                        task));
    }

    private void removeCommittedSubmit(String folded, long task) {
        jdbc.update("DELETE FROM \"CATALOG\".\"COMPANY_ALIASES\" WHERE \"ALIAS_FOLDED\"=?", folded);
        jdbc.update("DELETE FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"=?", folded);
        jdbc.update("DELETE FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"ID\"=?", task);
    }

    @Test
    void aliasHitsLinkBeforeSearchListenerWithoutEnqueuingAndNeverBecomeUserLinks() {
        long company = company("Synthetic canonical employer", "SEED");
        alias("COMPANY", "Synthetic raw employer", company);
        long title = title("SEED");
        alias("TITLE", "Synthetic raw title", title);
        long candidate = candidate();
        var profile =
                candidates.editExperience(
                        candidate,
                        0,
                        null,
                        experience("Synthetic raw employer", "Synthetic raw title"),
                        "SAVE",
                        "synthetic-editor");
        var row = links.experiences(candidate).getFirst();
        assertThat(row.companyId()).isEqualTo(company);
        assertThat(row.titleId()).isEqualTo(title);
        assertThat(row.normalizationOrigin()).isNull();
        assertThat(row.companyNormalized()).isEqualTo("Synthetic canonical employer");
        assertThat(json.readTree(row.industryCodes()).get(0).asString()).isEqualTo("RETAIL");
        assertThat(json.readTree(row.functionCodes()).get(0).asString()).isEqualTo("ENGINEERING");
        assertThat(row.seniorityCode()).isEqualTo("SENIOR");
        assertThat(row.scopeCode()).isEqualTo("COMPANY");
        assertThat(profile.experiences()).hasSize(1);
        assertThat(countTasks()).isZero();
        assertThat(indexText(candidate)).contains("synthetic canonical employer");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"COMPANY_IDS\"=ARRAY[?]::bigint[] FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                                Boolean.class,
                                company,
                                candidate))
                .isTrue();
    }

    @Test
    void aiAliasHitSetsAiOriginWhileBackfillDeduplicatesMissesByKindAndFoldedRaw() {
        long ai = company("Synthetic AI employer", "AI");
        alias("COMPANY", "Synthetic raw employer", ai);
        long id = candidate();
        candidates.editExperience(
                id,
                0,
                null,
                experience("Synthetic raw employer", null),
                "SAVE",
                "synthetic-editor");
        assertThat(links.experiences(id).getFirst().normalizationOrigin()).isEqualTo("AI");
        long second = candidate();
        insertExperience(second, "  Synthetic Missing  ", "Synthetic Missing", null);
        insertExperience(second, "synthetic missing", " SYNTHETIC MISSING ", null);
        long last = linker.backfillBatch(0, second, 100);
        assertThat(last).isEqualTo(second);
        assertThat(countTasks()).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"KIND\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\" ORDER BY \"KIND\"",
                                String.class))
                .containsExactly("COMPANY", "TITLE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"CONTEXT_COMPANY\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"KIND\"='TITLE'",
                                String.class))
                .isIn("  Synthetic Missing  ", "synthetic missing");
        linker.backfillBatch(0, second, 1);
        assertThat(countTasks()).isEqualTo(2);
    }

    @Test
    void claimsExposeOnlyApprovedFieldsAndReclaimExpiredLeases() throws Exception {
        tasks.enqueue("COMPANY", "synthetic employer", "Synthetic Employer", null);
        tasks.enqueue("TITLE", "synthetic role", "Synthetic Role", "Synthetic Employer");
        var response =
                mvc.perform(
                                post("/internal/normalization/v1/claims")
                                        .header("Authorization", "Bearer " + TOKEN)
                                        .contentType("application/json")
                                        .content("{\"max\":50}"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.taxonomyVersion").value("cv-taxonomy/0"))
                        .andExpect(jsonPath("$.taxonomy.FUNCTION").isArray())
                        .andExpect(jsonPath("$.taxonomy.SENIORITY").isArray())
                        .andExpect(jsonPath("$.taxonomy.INDUSTRY").isArray())
                        .andExpect(jsonPath("$.taxonomy.SCOPE").isArray())
                        .andReturn();
        var payload = json.readTree(response.getResponse().getContentAsString());
        for (var item : payload.path("items")) {
            assertThat(item.properties().stream().map(Map.Entry::getKey).toList())
                    .containsExactlyInAnyOrderElementsOf(
                            item.path("kind").asString().equals("COMPANY")
                                    ? List.of("id", "kind", "raw")
                                    : List.of("id", "kind", "raw", "contextCompany"));
        }
        assertThat(service.claim(50)).isNull();
        when(clock.clock()).thenReturn(Clock.fixed(NOW.plusSeconds(121), ZoneOffset.UTC));
        var reclaimed = service.claim(50);
        assertThat(reclaimed.items()).hasSize(2);
        assertThat(reclaimed.token().toString()).isNotEqualTo(payload.path("token").asString());
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"ATTEMPTS\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\"",
                                Integer.class))
                .containsOnly(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentClaimsSkipRowsLockedByAnotherClaim() throws Exception {
        var tx = new TransactionTemplate(transactions);
        var suffix = UUID.randomUUID().toString();
        tx.executeWithoutResult(
                s -> {
                    tasks.enqueue("COMPANY", suffix + "a", "Synthetic A", null);
                    tasks.enqueue("COMPANY", suffix + "b", "Synthetic B", null);
                });
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () ->
                                    tx.execute(
                                            s -> {
                                                var claim = service.claim(1);
                                                held.countDown();
                                                try {
                                                    if (!release.await(10, TimeUnit.SECONDS))
                                                        throw new AssertionError(
                                                                "Claim lock timeout");
                                                } catch (InterruptedException interrupted) {
                                                    Thread.currentThread().interrupt();
                                                    throw new AssertionError(interrupted);
                                                }
                                                return claim;
                                            }));
            try {
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> service.claim(1)).get(5, TimeUnit.SECONDS);
                assertThat(second.items()).hasSize(1);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).items().getFirst().id())
                        .isNotEqualTo(second.items().getFirst().id());
            } finally {
                release.countDown();
            }
        } finally {
            jdbc.update(
                    "DELETE FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"ALIAS_FOLDED\" IN (?,?)",
                    suffix + "a",
                    suffix + "b");
        }
    }

    @Test
    void validResultsCreateAliasesParentAndLinksAcrossCandidatesAndRebuildBeforeCommit() {
        long first = candidate(), second = candidate();
        insertExperience(first, "Aster Works Co., Ltd", "Senior Engineer", null);
        insertExperience(second, " ASTER Works Co., Ltd ", "Senior Engineer", null);
        events.publishEvent(new CandidateChanged(first));
        events.publishEvent(new CandidateChanged(second));
        var claim = service.claim(50);
        assertThat(claim.items()).hasSize(2);
        long companyTask = task(claim, "COMPANY"), titleTask = task(claim, "TITLE");
        var reply =
                service.submit(
                        claim.token(),
                        List.of(
                                companyResult(
                                        companyTask,
                                        "Aster Works",
                                        "Synthetic Parent",
                                        "MANUFACTURING"),
                                titleResult(
                                        titleTask,
                                        "[\"ENGINEERING\"]",
                                        "SENIOR",
                                        "COMPANY",
                                        null)));
        assertThat(reply.accepted()).isEqualTo(2);
        assertThat(reply.rejected()).isEmpty();
        long company =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME\"='Aster Works'",
                        Long.class);
        long parent =
                jdbc.queryForObject(
                        "SELECT \"PARENT_ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"ID\"=?",
                        Long.class,
                        company);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"INDUSTRY_CODE\" IS NULL AND \"ORIGIN\"='AI' FROM \"CATALOG\".\"COMPANIES\" WHERE \"ID\"=?",
                                Boolean.class,
                                parent))
                .isTrue();
        for (long id : List.of(first, second)) {
            var row = links.experiences(id).getFirst();
            assertThat(row.companyId()).isEqualTo(company);
            assertThat(row.titleId()).isNotNull();
            assertThat(row.normalizationOrigin()).isEqualTo("AI");
            assertThat(row.companyNormalized()).isEqualTo("Aster Works (Synthetic Parent)");
            assertThat(json.readTree(row.industryCodes()).get(0).asString())
                    .isEqualTo("MANUFACTURING");
            assertThat(json.readTree(row.functionCodes()).get(0).asString())
                    .isEqualTo("ENGINEERING");
            assertThat(row.seniorityCode()).isEqualTo("SENIOR");
            assertThat(row.scopeCode()).isEqualTo("COMPANY");
            assertThat(indexText(id)).contains("aster works", "synthetic parent");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"COMPANY_IDS\"=ARRAY[?]::bigint[] AND \"PARENT_COMPANY_IDS\"=ARRAY[?]::bigint[] AND \"HAS_AI_NORMALIZATION\" FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                                    Boolean.class,
                                    company,
                                    parent,
                                    id))
                    .isTrue();
        }
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"STATE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\"",
                                String.class))
                .containsOnly("DONE");
    }

    @Test
    void userLinksAndExistingUserOrSeedCatalogCodesRemainUntouched() {
        long userCompany = company("Synthetic protected employer", "USER");
        long seedCompany = company("Synthetic seed employer", "SEED");
        long userTitle = title("USER");
        long protectedCandidate = candidate();
        insertExperience(
                protectedCandidate, "Synthetic raw employer", "Synthetic raw title", "USER");
        var protectedExperience = links.experiences(protectedCandidate).getFirst();
        links.normalize(
                protectedCandidate,
                protectedExperience.id(),
                new CatalogLinks.Normalization(
                        userCompany,
                        userTitle,
                        "REGION",
                        "USER",
                        "Synthetic protected display",
                        List.of("IT"),
                        List.of("RETAIL"),
                        "HEAD"));
        var before = links.experiences(protectedCandidate);
        long automaticCandidate = candidate();
        insertExperience(automaticCandidate, "Synthetic raw employer", "Synthetic raw title", null);
        long seedCandidate = candidate();
        insertExperience(seedCandidate, "Synthetic seed alias", null, null);
        events.publishEvent(new CandidateChanged(automaticCandidate));
        events.publishEvent(new CandidateChanged(seedCandidate));
        var claim = service.claim(50);
        var results =
                claim.items().stream()
                        .map(
                                item ->
                                        item.kind().equals("TITLE")
                                                ? titleResult(
                                                        item.id(), "[\"IT\"]", "HEAD", null, null)
                                                : companyResult(
                                                        item.id(),
                                                        item.raw().equals("Synthetic seed alias")
                                                                ? "Synthetic seed employer"
                                                                : "Synthetic protected employer",
                                                        "Synthetic Proposed Parent",
                                                        "MANUFACTURING"))
                        .toList();
        assertThat(service.submit(claim.token(), results).accepted()).isEqualTo(3);
        assertThat(links.experiences(protectedCandidate)).isEqualTo(before);
        assertThat(links.experiences(automaticCandidate).getFirst().companyId())
                .isEqualTo(userCompany);
        assertThat(links.experiences(automaticCandidate).getFirst().titleId()).isEqualTo(userTitle);
        assertThat(links.experiences(seedCandidate).getFirst().companyId()).isEqualTo(seedCompany);
        for (long id : List.of(userCompany, seedCompany))
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"INDUSTRY_CODE\"='RETAIL' AND \"PARENT_ID\" IS NULL FROM \"CATALOG\".\"COMPANIES\" WHERE \"ID\"=?",
                                    Boolean.class,
                                    id))
                    .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"FUNCTION_CODES\"=ARRAY['ENGINEERING']::text[] AND \"SENIORITY_CODE\"='SENIOR' AND \"ORIGIN\"='USER' FROM \"CATALOG\".\"TITLES\" WHERE \"ID\"=?",
                                Boolean.class,
                                userTitle))
                .isTrue();
    }

    @Test
    void submitReplacesAiLinksButPreservesExistingSeedAndUserEntryLinks() {
        long oldAi = company("Synthetic old AI", "AI");
        long oldSeed = company("Synthetic old seed", "SEED");
        long oldUser = company("Synthetic old user", "USER");
        var ids = new java.util.ArrayList<Long>();
        for (long existing : List.of(oldAi, oldSeed, oldUser)) {
            long id = candidate();
            var experience = insertExperience(id, "Synthetic shared alias", null, "AI");
            links.normalize(
                    id,
                    experience,
                    new CatalogLinks.Normalization(
                            existing,
                            null,
                            null,
                            "AI",
                            "Synthetic existing display",
                            List.of(),
                            List.of("RETAIL"),
                            null));
            ids.add(id);
        }
        tasks.enqueue("COMPANY", "synthetic shared alias", "Synthetic shared alias", null);
        var claim = service.claim(1);
        assertThat(
                        service.submit(
                                        claim.token(),
                                        List.of(
                                                companyResult(
                                                        task(claim, "COMPANY"),
                                                        "Synthetic replacement",
                                                        null,
                                                        "MANUFACTURING")))
                                .accepted())
                .isEqualTo(1);
        long replacement =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='synthetic replacement'",
                        Long.class);
        assertThat(links.experiences(ids.get(0)).getFirst().companyId()).isEqualTo(replacement);
        assertThat(links.experiences(ids.get(1)).getFirst().companyId()).isEqualTo(oldSeed);
        assertThat(links.experiences(ids.get(2)).getFirst().companyId()).isEqualTo(oldUser);
        assertThat(indexText(ids.get(0))).contains("synthetic replacement");
    }

    @Test
    void invalidCodesMissingOtherLabelAndTooManyFunctionsRetryThenFail() {
        tasks.enqueue("COMPANY", "synthetic invalid company", "Synthetic Invalid Company", null);
        tasks.enqueue("COMPANY", "synthetic other company", "Synthetic Other Company", null);
        tasks.enqueue("TITLE", "synthetic invalid title", "Synthetic Invalid Title", null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = service.claim(50);
            var results =
                    claim.items().stream()
                            .map(
                                    item ->
                                            item.kind().equals("TITLE")
                                                    ? titleResult(
                                                            item.id(),
                                                            "[\"IT\",\"HR\",\"ENGINEERING\"]",
                                                            "SENIOR",
                                                            null,
                                                            null)
                                                    : companyResult(
                                                            item.id(),
                                                            item.raw(),
                                                            null,
                                                            item.raw().contains("Other")
                                                                    ? "OTHER"
                                                                    : "NOT_A_CODE"))
                            .toList();
            var reply = service.submit(claim.token(), results);
            assertThat(reply.accepted()).isZero();
            assertThat(reply.rejected())
                    .extracting(r -> r.code())
                    .containsExactlyInAnyOrder(
                            "INVALID_CODE", "PROPOSED_LABEL_REQUIRED", "INVALID_RESULT");
            Instant nextAttempt =
                    clock.clock()
                            .instant()
                            .plus(properties.getRetryBackoff().multipliedBy(attempt));
            assertThat(
                            jdbc.queryForList(
                                    "SELECT \"NEXT_ATTEMPT_AT\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\"",
                                    java.sql.Timestamp.class))
                    .allSatisfy(next -> assertThat(next.toInstant()).isEqualTo(nextAttempt));
            assertThat(
                            jdbc.queryForList(
                                    "SELECT \"STATE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\"",
                                    String.class))
                    .containsOnly(attempt == 3 ? "FAILED" : "PENDING");
            assertThat(service.claim(50)).isNull();
            when(clock.clock())
                    .thenReturn(Clock.fixed(NOW.plusSeconds(attempt * 300L), ZoneOffset.UTC));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"COMPLETED_AT\" IS NOT NULL AND \"TOKEN\" IS NULL",
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void titleValidationChecksActiveCodesDistinctFunctionsScopeKindAndOtherLabel() {
        var cases =
                List.of(
                        List.of("[\"IT\",\"IT\"]", "SENIOR", "COMPANY", "INVALID_RESULT"),
                        List.of("[\"OTHER\"]", "SENIOR", "COMPANY", "PROPOSED_LABEL_REQUIRED"),
                        List.of("[\"IT\"]", "UNKNOWN", "COMPANY", "INVALID_CODE"),
                        List.of("[\"IT\"]", "SENIOR", "UNKNOWN", "INVALID_CODE"),
                        List.of("[\"IT\"]", "SENIOR", "COMPANY", "INVALID_CODE"));
        var claimIds = new java.util.ArrayList<Long>();
        for (int i = 0; i < cases.size(); i++)
            tasks.enqueue("TITLE", "synthetic validation " + i, "Synthetic Validation " + i, null);
        var claim = service.claim(50);
        jdbc.update(
                "UPDATE \"CATALOG\".\"TAXONOMY_CODES\" SET \"ACTIVE\"=false WHERE \"KIND\"='SCOPE' AND \"CODE\"='COMPANY'");
        var results = new java.util.ArrayList<JsonNode>();
        for (int i = 0; i < cases.size(); i++) {
            var item = claim.items().get(i);
            claimIds.add(item.id());
            var value = cases.get(i);
            // OTHER validation runs after code checks, so use an active scope for that case.
            results.add(
                    titleResult(
                            item.id(),
                            value.get(0),
                            value.get(1),
                            i == 1 ? null : value.get(2),
                            null));
        }
        var reply = service.submit(claim.token(), results);
        assertThat(reply.rejected())
                .extracting(r -> r.code())
                .containsExactlyElementsOf(cases.stream().map(value -> value.get(3)).toList());
        assertThat(reply.accepted()).isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"STATE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"ID\"=ANY(?::bigint[])",
                                String.class,
                                (Object) claimIds.toArray(Long[]::new)))
                .containsOnly("PENDING");
    }

    @Test
    void otherWithProposedLabelIsAcceptedAndInvalidNamesKindsAndParentCyclesAreRejected() {
        tasks.enqueue("COMPANY", "synthetic other", "Synthetic Other", null);
        tasks.enqueue("TITLE", "synthetic other role", "Synthetic Other Role", null);
        var claim = service.claim(50);
        var company =
                (ObjectNode)
                        companyResult(task(claim, "COMPANY"), "Synthetic Other", null, "OTHER");
        ((ObjectNode) company.path("company")).put("proposedLabel", "Synthetic industry");
        var reply =
                service.submit(
                        claim.token(),
                        List.of(
                                company,
                                titleResult(
                                        task(claim, "TITLE"),
                                        "[\"OTHER\",\"IT\"]",
                                        "STAFF",
                                        null,
                                        "Synthetic function")));
        assertThat(reply.accepted()).isEqualTo(2);
        assertThat(reply.rejected()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"PROPOSED_LABEL\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='synthetic other'",
                                String.class))
                .isEqualTo("Synthetic industry");

        long root = company("Synthetic Root", "AI");
        long child = company("Synthetic Child", "AI");
        jdbc.update(
                "UPDATE \"CATALOG\".\"COMPANIES\" SET \"PARENT_ID\"=? WHERE \"ID\"=?", root, child);
        var names =
                List.of(
                        "",
                        "x".repeat(256),
                        "Synthetic Self",
                        "Synthetic Root",
                        "Synthetic Wrong Kind");
        for (int i = 0; i < names.size(); i++)
            tasks.enqueue("COMPANY", "synthetic invalid " + i, "Synthetic Invalid " + i, null);
        var next = service.claim(50);
        var invalid = new java.util.ArrayList<JsonNode>();
        for (int i = 0; i < names.size(); i++)
            invalid.add(
                    i == 4
                            ? titleResult(next.items().get(i).id(), "[\"IT\"]", "STAFF", null, null)
                            : companyResult(
                                    next.items().get(i).id(),
                                    names.get(i),
                                    i == 2 ? "Synthetic Self" : i == 3 ? "Synthetic Child" : null,
                                    "RETAIL"));
        assertThat(service.submit(next.token(), invalid).rejected())
                .extracting(r -> r.code())
                .containsOnly("INVALID_RESULT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"PARENT_ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"ID\"=?",
                                Long.class,
                                root))
                .isNull();
    }

    @Test
    void workerErrorUsesRetryPathAndUnknownIdsAreRejectedWithoutTouchingOtherTasks() {
        tasks.enqueue("TITLE", "synthetic role", "Synthetic Role", null);
        var claim = service.claim(1);
        long id = claim.items().getFirst().id();
        var reply =
                service.submit(
                        claim.token(),
                        List.of(
                                json.readTree("{\"id\":-1,\"error\":\"LLM_INVALID_JSON\"}"),
                                json.readTree(
                                        "{\"id\":" + id + ",\"error\":\"LLM_INVALID_JSON\"}")));
        assertThat(reply.accepted()).isZero();
        assertThat(reply.rejected())
                .extracting(r -> r.code())
                .containsExactly("UNKNOWN_TASK", "LLM_INVALID_JSON");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ERROR_CODE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"ID\"=?",
                                String.class,
                                id))
                .isEqualTo("LLM_INVALID_JSON");
    }

    @Test
    void expiredFinalAttemptBecomesFailedWithoutAnotherClaim() {
        tasks.enqueue("TITLE", "synthetic exhausted", "Synthetic Exhausted", null);
        jdbc.update("UPDATE \"CATALOG\".\"NORMALIZATION_TASKS\" SET \"MAX_ATTEMPTS\"=1");
        service.claim(1);
        when(clock.clock()).thenReturn(Clock.fixed(NOW.plusSeconds(121), ZoneOffset.UTC));
        assertThat(service.claim(1)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATE\" FROM \"CATALOG\".\"NORMALIZATION_TASKS\"",
                                String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void wrongWorkerDisabledAndWrongOrExpiredLeaseHaveDistinctStatuses() throws Exception {
        mvc.perform(
                        post("/internal/normalization/v1/claims")
                                .header("Authorization", "Bearer " + "x".repeat(43))
                                .contentType("application/json")
                                .content("{\"max\":1}"))
                .andExpect(status().isUnauthorized());
        properties.setEnabled(false);
        mvc.perform(
                        post("/internal/normalization/v1/claims")
                                .contentType("application/json")
                                .content("{\"max\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        properties.setEnabled(true);
        mvc.perform(
                        put("/internal/normalization/v1/results")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType("application/json")
                                .content(
                                        "{\"token\":\""
                                                + UUID.randomUUID()
                                                + "\",\"results\":[{\"id\":1,\"error\":\"LLM_INVALID_JSON\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_LOST"));
        tasks.enqueue("TITLE", "synthetic expired", "Synthetic Expired", null);
        var claim = service.claim(1);
        when(clock.clock()).thenReturn(Clock.fixed(NOW.plusSeconds(121), ZoneOffset.UTC));
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        claim.token(),
                                        List.of(
                                                titleResult(
                                                        task(claim, "TITLE"),
                                                        "[\"IT\"]",
                                                        "SENIOR",
                                                        null,
                                                        null))))
                .isInstanceOf(NormalizationFailure.class)
                .hasMessage("LEASE_LOST");
    }

    @Test
    void httpEnforcesBodyLimitClaimBoundsAndStrictJson() throws Exception {
        properties.setMaxBodyBytes(16);
        mvc.perform(
                        post("/internal/normalization/v1/claims")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType("application/json")
                                .content(" ".repeat(17)))
                .andExpect(status().is(413));
        for (String body :
                List.of(
                        "{\"max\":0}",
                        "{\"max\":51}",
                        "{\"max\":1.5}",
                        "{\"max\":\"1\"}",
                        "{} {}",
                        "[]",
                        "{")) {
            mvc.perform(
                            post("/internal/normalization/v1/claims")
                                    .header("Authorization", "Bearer " + TOKEN)
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        post("/internal/normalization/v1/claims")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType("text/plain")
                                .content("{\"max\":1}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    private long candidate() {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO \"CANDIDATE\".\"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"",
                        Long.class);
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"PROFILES\" (\"CANDIDATE_ID\",\"BASIC\",\"FULL_NAME\") VALUES (?,'{}','Synthetic Candidate')",
                id);
        return id;
    }

    private UUID insertExperience(long candidate, String company, String title, String origin) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"EXPERIENCES\" (\"ID\",\"CANDIDATE_ID\",\"COMPANY_RAW\",\"TITLE_RAW\",\"DATA\",\"STATE\",\"ORIGIN\",\"NORMALIZATION_ORIGIN\") VALUES (?,?,?,?,?::jsonb,'UNVERIFIED','MAPPING',?)",
                id,
                candidate,
                company,
                title,
                experience(company, title).toString(),
                origin);
        return id;
    }

    private ObjectNode experience(String company, String title) {
        var result =
                (ObjectNode)
                        json.readTree(
                                "{\"period\":{\"start\":{\"year\":2024,\"month\":1,\"precision\":\"MONTH\"},\"end\":{\"year\":2024,\"month\":3,\"precision\":\"MONTH\"},\"ongoingAsStated\":false}}");
        if (company != null) result.putObject("company").put("raw", company);
        if (title != null) result.putObject("title").put("raw", title);
        else result.putObject("title");
        return result;
    }

    private long company(String name, String origin) {
        return jdbc.queryForObject(
                "INSERT INTO \"CATALOG\".\"COMPANIES\" (\"NAME\",\"NAME_FOLDED\",\"INDUSTRY_CODE\",\"ORIGIN\") VALUES (?,?,'RETAIL',?) RETURNING \"ID\"",
                Long.class,
                name,
                Fold.fold(name),
                origin);
    }

    private long title(String origin) {
        return jdbc.queryForObject(
                "INSERT INTO \"CATALOG\".\"TITLES\" (\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"SCOPE_CODE\",\"ORIGIN\") VALUES ('Synthetic raw title','synthetic raw title','{ENGINEERING}','SENIOR','COMPANY',?) RETURNING \"ID\"",
                Long.class,
                origin);
    }

    private void alias(String kind, String raw, long entry) {
        jdbc.update(
                "INSERT INTO \"CATALOG\".\""
                        + kind
                        + "_ALIASES\" (\"ALIAS_FOLDED\",\"ALIAS_RAW\",\""
                        + kind
                        + "_ID\",\"ORIGIN\") VALUES (?,?,?,'SEED')",
                Fold.fold(raw),
                raw,
                entry);
    }

    private int countTasks() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM \"CATALOG\".\"NORMALIZATION_TASKS\"", Integer.class);
    }

    private String indexText(long id) {
        return jdbc.queryForObject(
                "SELECT \"SEARCH_TEXT\" FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                String.class,
                id);
    }

    private long task(Claim claim, String kind) {
        return claim.items().stream()
                .filter(item -> item.kind().equals(kind))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private JsonNode companyResult(long id, String name, String parent, String industry) {
        var result = json.createObjectNode().put("id", id);
        result.putObject("company")
                .put("name", name)
                .put("parent", parent)
                .put("industry", industry);
        return result;
    }

    private JsonNode titleResult(
            long id, String functions, String seniority, String scope, String label) {
        var result = json.createObjectNode().put("id", id);
        result.putObject("title")
                .put("seniority", seniority)
                .put("scope", scope)
                .put("proposedLabel", label)
                .set("functions", json.readTree(functions));
        return result;
    }
}
