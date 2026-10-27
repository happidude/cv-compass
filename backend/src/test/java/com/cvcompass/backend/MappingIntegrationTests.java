package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.ingestion.dto.OcrAttemptKey;
import com.cvcompass.backend.ingestion.dto.OcrClaimRequest;
import com.cvcompass.backend.ingestion.entity.CvDocument;
import com.cvcompass.backend.ingestion.entity.CvProcessingJob;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.service.OcrJobService;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingDefinition;
import com.cvcompass.backend.mapping.dto.MappingDefinition.Field;
import com.cvcompass.backend.mapping.dto.MappingResult;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.mapping.service.MappingWorker;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cv.ocr.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MappingIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static final String HASH = "a".repeat(64);
    @Autowired MappingService mapping;
    @Autowired MappingWorker worker;
    @Autowired OcrJobService ocr;
    @Autowired OcrExtractionReader inputs;
    @Autowired JdbcTemplate jdbc;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(MappingIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @BeforeEach
    void resetMappingOnly() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(DATABASE.schemaName());
        jdbc.execute("TRUNCATE \"CV_MAPPING_RESULTS\",\"CV_MAPPING_JOBS\"");
        jdbc.update(
                "UPDATE \"CV_MAPPING_SETTINGS\" SET \"ACTIVE_CONFIG_ID\"=NULL,\"SCAN_AFTER_ID\"=0");
    }

    private MappingDefinition definition() {
        return new MappingDefinition(
                1, List.of(new Field("sample", "STRING", "PAGE", "FIRST_LINE", null)));
    }

    private long activate() {
        long id = mapping.register("synthetic-" + UUID.randomUUID(), definition());
        mapping.activate(id);
        return id;
    }

    private long seed() {
        return seed("success");
    }

    private long seed(String ocrStatus) {
        var doc = new CvDocument();
        doc.setOriginalFilename("synthetic.pdf");
        doc.setStoragePath(UUID.randomUUID() + ".pdf");
        doc.setFileSize(1024L);
        doc.setMimeType("application/pdf");
        doc.setSha256(HASH);
        doc.setStatus(DocumentStatus.PENDING);
        documents.saveAndFlush(doc);
        var job = new CvProcessingJob();
        job.setDocument(doc);
        job.setStatus(JobStatus.PENDING);
        job.setProfileRevision("synthetic-" + UUID.randomUUID());
        job.setGeneration(1);
        job.setMaxAttempts(3);
        job.setAttemptsStarted(0);
        job.setNextAttemptAt(Instant.parse("2026-01-01T00:00:00Z"));
        job.setAdmissionSnapshot(Map.of("synthetic_only", true));
        jobs.saveAndFlush(job);
        var request =
                new OcrClaimRequest(
                        "synthetic_worker", UUID.randomUUID(), HASH, job.getProfileRevision());
        var claim = ocr.claim(request).orElseThrow();
        ObjectNode envelope = (ObjectNode) json.readTree(OcrFixtures.success(claim));
        ObjectNode result = (ObjectNode) envelope.get("ocr_result");
        result.put("schema_version", "cv-compass.ocr/2.0");
        var page = (ObjectNode) result.get("pages").get(0);
        page.remove("text");
        page.put("raw_text", "😀 Synthetic e\u0301\nJava");
        if (!"success".equals(ocrStatus)) {
            ObjectNode failedPage = page.deepCopy();
            failedPage.put("status", "failed").put("method", "none").put("raw_text", "");
            failedPage.putObject("error").put("code", "OCR_FAILED").put("stage", "ocr");
            result.put("status", ocrStatus);
            if ("partial".equals(ocrStatus)) {
                failedPage.put("page_number", 2);
                result.withArray("pages").add(failedPage);
                result.put("page_count", 2);
                envelope.put("exit_code", 1);
            } else {
                result.withArray("pages").removeAll().add(failedPage);
                envelope.put("exit_code", 2);
            }
        }
        return ocr.complete(
                        new OcrAttemptKey(claim.attemptId(), request.workerId(), HASH),
                        json.writeValueAsString(envelope))
                .extractionId();
    }

    private MappingClaim queued(long extraction, long config) {
        jdbc.update(
                """
                INSERT INTO "CV_MAPPING_JOBS" ("EXTRACTION_ID","CONFIG_ID","ENGINE_VERSION") VALUES (?,?,'literal-mapping/1.0')
                """,
                extraction,
                config);
        return mapping.claim().orElseThrow();
    }

    private void expire(MappingClaim claim) {
        jdbc.update(
                """
                UPDATE "CV_MAPPING_JOBS" SET "LEASE_EXPIRES_AT"=clock_timestamp()-interval '1 second' WHERE "ID"=?
                """,
                claim.jobId());
    }

    private void ready(MappingClaim claim) {
        jdbc.update(
                """
                UPDATE "CV_MAPPING_JOBS" SET "NEXT_ATTEMPT_AT"=clock_timestamp()-interval '1 second' WHERE "ID"=?
                """,
                claim.jobId());
    }

    @Test
    void storedOcrToWorkerResultPreservesOcrAndCandidateState() {
        long extraction = seed();
        String before =
                jdbc.queryForObject(
                        "SELECT row_to_json(e)::text FROM \"CV_EXTRACTIONS\" e WHERE \"ID\"=?",
                        String.class,
                        extraction);
        var statuses =
                jdbc.queryForList(
                        "SELECT \"ID\",\"STATUS\" FROM \"CV_PROCESSING_JOBS\" ORDER BY \"ID\"");
        int candidates = jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATES\"", Integer.class);
        activate();
        mapping.scan(100);
        assertThat(worker.runOne()).isTrue();
        // Locate by fixed extraction, regardless of other synthetic inputs retained by the class.
        while (worker.runOne()) {
            /* bounded by the finite queue */
        }
        long id =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"=?",
                        Long.class,
                        extraction);
        var result = mapping.result(id).orElseThrow();
        assertThat(result.extractionId()).isEqualTo(extraction);
        assertThat(result.quality()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.values().getFirst().value()).isEqualTo("😀 Synthetic e\u0301");
        assertThat(result.values().getFirst().evidence().end()).isEqualTo(14);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT row_to_json(e)::text FROM \"CV_EXTRACTIONS\" e WHERE \"ID\"=?",
                                String.class,
                                extraction))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"ID\",\"STATUS\" FROM \"CV_PROCESSING_JOBS\" ORDER BY \"ID\""))
                .isEqualTo(statuses);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATES\"", Integer.class))
                .isEqualTo(candidates);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CV_EXTRACTIONS\" SET \"STRUCTURED_DATA\"='{}'::jsonb WHERE \"ID\"=?",
                                        extraction))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void invalidConfigCannotActivateAndActivatedRevisionCannotChange() {
        long bad =
                jdbc.queryForObject(
                        """
                INSERT INTO "CV_MAPPING_CONFIGS" ("REVISION","DEFINITION") VALUES (?, '{"schemaVersion":99,"fields":[]}') RETURNING "ID"
                """,
                        Long.class,
                        "bad-" + UUID.randomUUID());
        assertThatThrownBy(() -> mapping.activate(bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ACTIVE_CONFIG_ID\" FROM \"CV_MAPPING_SETTINGS\"",
                                Long.class))
                .isNull();
        long config = activate();
        String revision =
                jdbc.queryForObject(
                        "SELECT \"REVISION\" FROM \"CV_MAPPING_CONFIGS\" WHERE \"ID\"=?",
                        String.class,
                        config);
        assertThat(mapping.register(revision, definition())).isEqualTo(config);
        assertThatThrownBy(
                        () ->
                                mapping.register(
                                        revision,
                                        new MappingDefinition(
                                                1,
                                                List.of(
                                                        new Field(
                                                                "different",
                                                                "STRING",
                                                                "PAGE",
                                                                "FIRST_LINE",
                                                                null)))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CV_MAPPING_CONFIGS\" SET \"DEFINITION\"='{}' WHERE \"ID\"=?",
                                        config))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM \"CV_MAPPING_CONFIGS\" WHERE \"ID\"=?",
                                        config))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> mapping.register("invalid", new MappingDefinition(9, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enqueueRepeatedSweepAndServiceRestartDoNotDuplicateJobs() {
        seed();
        activate();
        while (mapping.scan(2) == 2) {}
        int count = jdbc.queryForObject("SELECT count(*) FROM \"CV_MAPPING_JOBS\"", Integer.class);
        // A new service has no in-memory cursor, exercising durable settings after restart.
        var restarted =
                new MappingService(
                        new com.cvcompass.backend.mapping.repository.MappingRepository(jdbc, json),
                        inputs,
                        new com.cvcompass.backend.mapping.service.MappingEngine(),
                        json,
                        transactions);
        while (restarted.scan(2) == 2) {}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_MAPPING_JOBS\"", Integer.class))
                .isEqualTo(count);
    }

    @Test
    void reconciliationFindsLowerIdCommittedAfterHigherId() throws Exception {
        long source = seed();
        activate();
        String url =
                System.getenv()
                        .getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/cv_compass");
        try (var connection =
                DriverManager.getConnection(
                        url,
                        System.getenv().getOrDefault("TEST_DB_USERNAME", "cvcompass"),
                        System.getenv("TEST_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            long low;
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + DATABASE.schemaName());
                try (var rows =
                        statement.executeQuery(
                                """
                        INSERT INTO "CV_EXTRACTIONS" ("CV_DOCUMENT_ID","PROCESSING_JOB_ID","RAW_TEXT","EXTRACTION_METHOD")
                        SELECT "CV_DOCUMENT_ID","PROCESSING_JOB_ID",'Synthetic delayed','TEXT_EXTRACTION'
                        FROM "CV_EXTRACTIONS" WHERE "ID"=%d RETURNING "ID"
                        """
                                        .formatted(source))) {
                    rows.next();
                    low = rows.getLong(1);
                }
            }
            long high =
                    jdbc.queryForObject(
                            """
                    INSERT INTO "CV_EXTRACTIONS" ("CV_DOCUMENT_ID","PROCESSING_JOB_ID","RAW_TEXT","EXTRACTION_METHOD")
                    SELECT "CV_DOCUMENT_ID","PROCESSING_JOB_ID",'Synthetic later','TEXT_EXTRACTION'
                    FROM "CV_EXTRACTIONS" WHERE "ID"=? RETURNING "ID"
                    """,
                            Long.class,
                            source);
            assertThat(high).isGreaterThan(low);
            while (mapping.scan(1) == 1) {}
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"=?",
                                    Integer.class,
                                    low))
                    .isZero();
            connection.commit();
            while (mapping.scan(1) == 1) {}
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"=?",
                                    Integer.class,
                                    low))
                    .isEqualTo(1);
        }
    }

    @Test
    void parallelClaimHasSingleOwner() throws Exception {
        long extraction = seed();
        long config = activate();
        var first = queued(extraction, config);
        mapping.fail(first);
        ready(first);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return mapping.claim();
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return mapping.claim();
                            });
            int claims =
                    (a.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                            + (b.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(claims).isEqualTo(1);
        }
    }

    @Test
    void expiryFencesStaleAttemptAndRetriesAreFinite() {
        var first = queued(seed(), activate());
        var oldResult = mapping.process(first);
        expire(first);
        assertThatThrownBy(() -> mapping.complete(first, oldResult))
                .hasMessage("MAPPING_STALE_ATTEMPT");
        assertThat(mapping.reap(100)).isEqualTo(1);
        assertThatThrownBy(() -> mapping.complete(first, oldResult))
                .hasMessage("MAPPING_STALE_ATTEMPT");
        ready(first);
        var second = mapping.claim().orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> mapping.complete(first, oldResult))
                .hasMessage("MAPPING_STALE_ATTEMPT");
        mapping.fail(second);
        ready(second);
        var third = mapping.claim().orElseThrow();
        expire(third);
        mapping.reap(100);
        assertThat(mapping.claim()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?",
                                String.class,
                                third.jobId()))
                .isEqualTo("FAILED");
        assertThat(mapping.result(third.jobId())).isEmpty();
    }

    @Test
    void replaySameResultIsIdempotentAndDifferentResultConflicts() {
        var claim = queued(seed(), activate());
        var result = mapping.process(claim);
        mapping.complete(claim, result);
        expire(claim); // terminal replay is permitted after the accepted lease.
        mapping.complete(claim, result);
        var changed =
                new MappingResult(
                        result.schemaVersion(),
                        result.extractionId(),
                        result.ocrSchemaVersion(),
                        result.configId(),
                        result.configRevision(),
                        result.engineVersion(),
                        result.quality(),
                        result.incompleteSource(),
                        "invented",
                        result.values());
        assertThatThrownBy(() -> mapping.complete(claim, changed))
                .hasMessage("MAPPING_RESULT_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                                Integer.class,
                                claim.jobId()))
                .isEqualTo(1);
    }

    @Test
    void resultInsertAndCompletionRollBackTogetherOnDatabaseFailure() {
        var claim = queued(seed(), activate());
        var result = mapping.process(claim);
        jdbc.execute(
                """
                CREATE FUNCTION reject_mapping_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW."STATUS"='COMPLETED' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END; $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER "TR_TEST_MAPPING_FAIL" BEFORE UPDATE ON "CV_MAPPING_JOBS"
                FOR EACH ROW EXECUTE FUNCTION reject_mapping_completion()
                """);
        try {
            assertThatThrownBy(() -> mapping.complete(claim, result))
                    .isInstanceOf(DataAccessException.class);
            assertThat(mapping.result(claim.jobId())).isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"STATUS\" FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?",
                                    String.class,
                                    claim.jobId()))
                    .isEqualTo("PROCESSING");
        } finally {
            jdbc.execute("DROP TRIGGER \"TR_TEST_MAPPING_FAIL\" ON \"CV_MAPPING_JOBS\"");
            jdbc.execute("DROP FUNCTION reject_mapping_completion()");
        }
        mapping.complete(claim, result);
        assertThat(mapping.result(claim.jobId())).contains(result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?",
                                String.class,
                                claim.jobId()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void reaperAndCompletionRaceHasOneConsistentOutcome() throws Exception {
        var claim = queued(seed(), activate());
        var result = mapping.process(claim);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var complete =
                    pool.submit(
                            () -> {
                                barrier.await();
                                mapping.complete(claim, result);
                                return true;
                            });
            var reaper =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return mapping.reap(100);
                            });
            assertThat(complete.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reaper.get(10, TimeUnit.SECONDS)).isZero();
        }
        assertThat(mapping.result(claim.jobId())).contains(result);
        expire(claim);
        assertThat(mapping.reap(100)).isZero();
    }

    @Test
    void jobPinsRevisionAcrossActiveConfigChanges() {
        var claim = queued(seed(), activate());
        long original = claim.configId();
        activate();
        var result = mapping.process(claim);
        mapping.complete(claim, result);
        assertThat(result.configId()).isEqualTo(original);
        assertThat(mapping.result(claim.jobId())).contains(result);
    }

    @Test
    void inputFingerprintDetectsSourceChangesAndResultsAreImmutable() {
        var claim = queued(seed(), activate());
        var result = mapping.process(claim);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"INPUT_SHA256\" FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?",
                                String.class,
                                claim.jobId()))
                .isEqualTo(result.inputSha256());
        mapping.complete(claim, result);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CV_MAPPING_RESULTS\" SET \"QUALITY\"='UNSUPPORTED_INPUT' WHERE \"JOB_ID\"=?",
                                        claim.jobId()))
                .isInstanceOf(DataAccessException.class);
        var changing = queued(seed(), claim.configId());
        var original = mapping.process(changing);
        jdbc.update(
                """
                UPDATE "CV_EXTRACTIONS" SET "OCR_RESULT"=jsonb_set("OCR_RESULT",'{pages,0,raw_text}','"Changed synthetic"')
                WHERE "ID"=?
                """,
                changing.extractionId());
        assertThatThrownBy(() -> mapping.complete(changing, original))
                .hasMessage("MAPPING_INPUT_CHANGED");
        mapping.fail(changing, false);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?",
                                String.class,
                                changing.jobId()))
                .isEqualTo("FAILED");
        assertThat(mapping.result(changing.jobId())).isEmpty();
    }

    @Test
    void scannerUsesFixedUpperBoundAndReconcilesNewIdsNextSweep() {
        seed();
        activate();
        assertThat(mapping.scan(1)).isEqualTo(1);
        long upper =
                jdbc.queryForObject(
                        "SELECT \"SCAN_UPPER_ID\" FROM \"CV_MAPPING_SETTINGS\"", Long.class);
        long newer = seed();
        assertThat(newer).isGreaterThan(upper);
        while (mapping.scan(1) == 1) {}
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"=?",
                                Integer.class,
                                newer))
                .isZero();
        mapping.scan(100);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"=?",
                                Integer.class,
                                newer))
                .isEqualTo(1);
    }

    @Test
    void resultAndClaimCannotSubstituteAnotherInputOrConfiguration() {
        var first = queued(seed(), activate());
        var second = queued(seed(), activate());
        var wrong = mapping.process(second);
        assertThatThrownBy(() -> mapping.complete(first, wrong))
                .hasMessage("MAPPING_RESULT_CONFLICT");
        assertThat(mapping.result(first.jobId())).isEmpty();
        var forged =
                new MappingClaim(
                        first.jobId(),
                        second.extractionId(),
                        second.configId(),
                        second.configRevision(),
                        first.engineVersion(),
                        first.attempt(),
                        first.token(),
                        first.leaseExpiresAt(),
                        second.definition());
        var actual = mapping.process(forged);
        assertThat(actual.extractionId()).isEqualTo(first.extractionId());
        assertThat(actual.configId()).isEqualTo(first.configId());
        mapping.complete(first, actual);
        assertThat(mapping.result(first.jobId())).contains(actual);
    }

    @Test
    void completionRollsBackIfLeaseExpiresDuringResultWrite() {
        var claim = queued(seed(), activate());
        var result = mapping.process(claim);
        jdbc.execute(
                """
                CREATE FUNCTION delay_mapping_result() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    UPDATE "CV_MAPPING_JOBS" SET "LEASE_EXPIRES_AT"=clock_timestamp()-interval '1 second'
                    WHERE "ID"=NEW."JOB_ID";
                    RETURN NEW;
                END; $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER "TR_TEST_MAPPING_DELAY" BEFORE INSERT ON "CV_MAPPING_RESULTS"
                FOR EACH ROW EXECUTE FUNCTION delay_mapping_result()
                """);
        try {
            // Deterministically advance only this lease during the write; never change host/DB
            // time.
            // The completion must reread the lease from the locked row after writes.
            assertThatThrownBy(() -> mapping.complete(claim, result))
                    .hasMessage("MAPPING_STALE_ATTEMPT");
            assertThat(mapping.result(claim.jobId())).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER \"TR_TEST_MAPPING_DELAY\" ON \"CV_MAPPING_RESULTS\"");
            jdbc.execute("DROP FUNCTION delay_mapping_result()");
        }
    }

    @Test
    void expiredCompletionAndReaperRaceRejectsResult() throws Exception {
        var claim = queued(seed(), activate());
        var result = mapping.process(claim);
        expire(claim);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var completion =
                    pool.submit(
                            () -> {
                                barrier.await();
                                try {
                                    mapping.complete(claim, result);
                                    return "accepted";
                                } catch (IllegalStateException stale) {
                                    return stale.getMessage();
                                }
                            });
            var reaper =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return mapping.reap(100);
                            });
            assertThat(completion.get(10, TimeUnit.SECONDS)).isEqualTo("MAPPING_STALE_ATTEMPT");
            assertThat(reaper.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(mapping.result(claim.jobId())).isEmpty();
    }

    @Test
    void persistedPartialFailedAndLegacyInputsRemainDistinct() {
        long extraction = seed("partial");
        var claim = queued(extraction, activate());
        var partial = mapping.process(claim);
        assertThat(partial.incompleteSource()).isTrue();
        assertThat(partial.values().getFirst().evidence().page()).isEqualTo(1);
        mapping.complete(claim, partial);
        long failed = seed("failed");
        var failedClaim = queued(failed, claim.configId());
        var rejected = mapping.process(failedClaim);
        assertThat(rejected.reason()).isEqualTo("OCR_UNUSABLE");
        assertThat(rejected.values()).isEmpty();
        mapping.complete(failedClaim, rejected);
        long legacy =
                jdbc.queryForObject(
                        """
                INSERT INTO "CV_EXTRACTIONS" ("CV_DOCUMENT_ID","PROCESSING_JOB_ID","RAW_TEXT","EXTRACTION_METHOD")
                SELECT "CV_DOCUMENT_ID","PROCESSING_JOB_ID",'Synthetic legacy','TEXT_EXTRACTION'
                FROM "CV_EXTRACTIONS" WHERE "ID"=? RETURNING "ID"
                """,
                        Long.class,
                        extraction);
        var legacyClaim = queued(legacy, claim.configId());
        var legacyResult = mapping.process(legacyClaim);
        assertThat(legacyResult.reason()).isEqualTo("LEGACY_UNPAGED");
        assertThat(legacyResult.values()).isEmpty();
        mapping.complete(legacyClaim, legacyResult);
        assertThat(inputs.find(extraction).orElseThrow().id()).isEqualTo(extraction);
    }
}
