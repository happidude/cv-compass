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
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.mapping.service.MappingWorker;
import java.nio.charset.StandardCharsets;
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
    @Autowired com.cvcompass.backend.mapping.config.MappingProperties mappingProperties;
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
        mappingProperties.setMaxAttempts(3);
        mappingProperties.setLeaseDuration(java.time.Duration.ofSeconds(120));
        mappingProperties.setHardDeadline(java.time.Duration.ofSeconds(120));
        mappingProperties.setRetryBackoff(java.time.Duration.ofSeconds(30));
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(DATABASE.schemaName());
        jdbc.execute(
                "TRUNCATE \"CANDIDATE_PROPOSALS\",\"CANDIDATE_PUBLICATIONS\",\"CANDIDATE_EXPERIENCES\",\"CV_MAPPING_RESULTS\",\"CV_MAPPING_JOBS\"");
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
        return seed(ocrStatus, null);
    }

    private long seed(String ocrStatus, Long existingDocument) {
        return seed(ocrStatus, existingDocument, "😀 Synthetic e\u0301\nJava");
    }

    private long seed(String ocrStatus, Long existingDocument, String rawText) {
        var doc =
                existingDocument == null
                        ? new CvDocument()
                        : documents.findById(existingDocument).orElseThrow();
        if (existingDocument == null) {
            doc.setOriginalFilename("synthetic.pdf");
            doc.setStoragePath(UUID.randomUUID() + ".pdf");
            doc.setFileSize(1024L);
            doc.setMimeType("application/pdf");
            doc.setSha256(HASH);
            doc.setStatus(DocumentStatus.PENDING);
            documents.saveAndFlush(doc);
        }
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
        page.put("raw_text", rawText);
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
                        transactions,
                        new com.cvcompass.backend.mapping.config.MappingProperties(),
                        new com.cvcompass.backend.mapping.service.ExperienceEngine(
                                new com.cvcompass.backend.mapping.service.MappingEngine()));
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

    @Test
    void reactivatingSameConfigurationPreservesSweepAndChangingItResets() throws Exception {
        seed();
        long config = activate();
        mapping.scan(1);
        var before = jdbc.queryForMap("SELECT * FROM \"CV_MAPPING_SETTINGS\"");
        assertThat(before.get("SCAN_AFTER_ID")).isNotEqualTo(0L);
        mapping.activate(config);
        assertThat(jdbc.queryForMap("SELECT * FROM \"CV_MAPPING_SETTINGS\"")).isEqualTo(before);
        var file = java.nio.file.Files.createTempFile("synthetic-mapping-config-", ".json");
        try {
            String revision =
                    jdbc.queryForObject(
                            "SELECT \"REVISION\" FROM \"CV_MAPPING_CONFIGS\" WHERE \"ID\"=?",
                            String.class,
                            config);
            String definition =
                    jdbc.queryForObject(
                            "SELECT \"DEFINITION\"::text FROM \"CV_MAPPING_CONFIGS\" WHERE \"ID\"=?",
                            String.class,
                            config);
            java.nio.file.Files.writeString(file, definition);
            new com.cvcompass.backend.mapping.config.MappingBootstrap(
                            mapping, json, file.toString(), revision)
                    .run(new org.springframework.boot.DefaultApplicationArguments());
            assertThat(jdbc.queryForMap("SELECT * FROM \"CV_MAPPING_SETTINGS\"")).isEqualTo(before);
        } finally {
            java.nio.file.Files.delete(file);
        }
        activate();
        assertThat(jdbc.queryForMap("SELECT * FROM \"CV_MAPPING_SETTINGS\""))
                .containsEntry("SCAN_AFTER_ID", 0L)
                .containsEntry("SCAN_UPPER_ID", null);
    }

    @Test
    void multipleOcrRevisionsDoNotBecomeMultipleBusinessFilesOrChangeBatch() {
        long first = seed();
        long document = inputs.find(first).orElseThrow().documentId();
        long batch =
                jdbc.queryForObject(
                        """
                INSERT INTO "CV_IMPORT_BATCHES" ("TOTAL_FILES","SUCCESS_COUNT","FAILED_COUNT","STATUS")
                VALUES (1,1,0,'COMPLETED') RETURNING "ID"
                """,
                        Long.class);
        jdbc.update("UPDATE \"CV_DOCUMENTS\" SET \"BATCH_ID\"=? WHERE \"ID\"=?", batch, document);
        long second = seed("success", document);
        assertThat(second).isNotEqualTo(first);
        var before = jdbc.queryForMap("SELECT * FROM \"CV_IMPORT_BATCHES\" WHERE \"ID\"=?", batch);
        activate();
        while (mapping.scan(1) == 1) {}
        while (mapping.scan(1) == 1) {}
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT count(*) FROM "CV_MAPPING_JOBS" j JOIN "CV_EXTRACTIONS" e ON e."ID"=j."EXTRACTION_ID"
                WHERE e."CV_DOCUMENT_ID"=?
                """,
                                Integer.class,
                                document))
                .isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM \"CV_IMPORT_BATCHES\" WHERE \"ID\"=?", batch))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_DOCUMENTS\" WHERE \"BATCH_ID\"=?",
                                Integer.class,
                                batch))
                .isEqualTo(1);
    }

    @Test
    void configuredAttemptBudgetLeaseAndBackoffReachSqlWithoutOldCaps() {
        long extraction = seed();
        mappingProperties.setMaxAttempts(5);
        mappingProperties.setLeaseDuration(java.time.Duration.ofSeconds(40));
        mappingProperties.setHardDeadline(java.time.Duration.ofSeconds(60));
        mappingProperties.setRetryBackoff(java.time.Duration.ofSeconds(5));
        activate();
        mapping.scan(101);
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT \"MAX_ATTEMPTS\" FROM \"CV_MAPPING_JOBS\"",
                                Integer.class))
                .containsExactly(5);
        // Retain just this synthetic job, leaving OCR/document rows untouched.
        jdbc.update("DELETE FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"<>?", extraction);
        var claim = mapping.claim().orElseThrow();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(claim.attempt()).isEqualTo(attempt);
            var gap =
                    jdbc.queryForObject(
                            """
                    SELECT extract(epoch FROM ("HARD_DEADLINE_AT"-"LEASE_EXPIRES_AT"))::int
                    FROM "CV_MAPPING_JOBS" WHERE "ID"=?
                    """,
                            Integer.class,
                            claim.jobId());
            assertThat(gap).isEqualTo(20);
            mapping.fail(claim);
            var backoff =
                    jdbc.queryForObject(
                            """
                    SELECT extract(epoch FROM ("NEXT_ATTEMPT_AT"-clock_timestamp())) FROM "CV_MAPPING_JOBS" WHERE "ID"=?
                    """,
                            Double.class,
                            claim.jobId());
            assertThat(backoff).isBetween(3.0, 5.1);
            if (attempt < 5) {
                ready(claim);
                claim = mapping.claim().orElseThrow();
            }
        }
        assertThat(mapping.claim()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?",
                                String.class,
                                claim.jobId()))
                .isEqualTo("FAILED");
    }

    @Test
    void experienceV2PersistsThroughWorkerWhileV1PayloadDigestAndReplayRemainUnchanged()
            throws Exception {
        var legacyClaim = queued(seed(), activate());
        var legacyResult = mapping.process(legacyClaim);
        mapping.complete(legacyClaim, legacyResult);
        var legacyRow =
                jdbc.queryForMap(
                        "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                        legacyClaim.jobId());
        com.cvcompass.backend.mapping.dto.ExperienceDefinition config;
        try (var stream = getClass().getResourceAsStream("/mapping/experience-config-v2.json")) {
            config =
                    json.readValue(
                            stream, com.cvcompass.backend.mapping.dto.ExperienceDefinition.class);
        }
        long configId = mapping.register("experience-" + UUID.randomUUID(), config);
        mapping.activate(configId);
        long extraction =
                seed(
                        "success",
                        null,
                        """
                WORK EXPERIENCE
                Employer: Northstar Devices
                Position: Engineering Manager
                Dates: 2018 - 2021
                Partner: Synthetic Client
                """);
        var ocrBefore =
                jdbc.queryForMap("SELECT * FROM \"CV_EXTRACTIONS\" WHERE \"ID\"=?", extraction);
        mapping.scan();
        while (worker.runOne()) {}
        long job =
                jdbc.queryForObject(
                        """
                SELECT "ID" FROM "CV_MAPPING_JOBS" WHERE "EXTRACTION_ID"=? AND "CONFIG_ID"=?
                """,
                        Long.class,
                        extraction,
                        configId);
        var outcome = mapping.result(job).orElseThrow();
        assertThat(outcome)
                .isInstanceOf(com.cvcompass.backend.mapping.dto.ExperienceMappingResult.class);
        var result = (com.cvcompass.backend.mapping.dto.ExperienceMappingResult) outcome;
        assertThat(result.schemaVersion()).isEqualTo(2);
        assertThat(result.experiences()).hasSize(1);
        assertThat(result.experiences().getFirst().company().normalized())
                .isEqualTo("northstar devices");
        assertThat(result.experiences().getFirst().title().functionCodes())
                .containsExactly("ENGINEERING");
        assertThat(result.experiences().getFirst().company().industryCodes())
                .containsExactly("MANUFACTURING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"SCHEMA_VERSION\" FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                                Integer.class,
                                job))
                .isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM \"CV_EXTRACTIONS\" WHERE \"ID\"=?", extraction))
                .isEqualTo(ocrBefore);
        mapping.complete(legacyClaim, legacyResult);
        assertThat(mapping.result(legacyClaim.jobId())).contains(legacyResult);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                                legacyClaim.jobId()))
                .isEqualTo(legacyRow);
        var v2Claim =
                new com.cvcompass.backend.mapping.repository.MappingRepository(jdbc, json)
                        .readClaim(job);
        mapping.complete(v2Claim, result);
        ObjectNode changed =
                (ObjectNode)
                        json.readTree(
                                com.cvcompass.backend.mapping.service.MappingDigests.canonical(
                                        result));
        ((ObjectNode) changed.get("experiences").get(0).get("company"))
                .put("normalized", "fabricated synthetic");
        var conflict =
                json.readValue(
                        changed.toString(),
                        com.cvcompass.backend.mapping.dto.ExperienceMappingResult.class);
        assertThatThrownBy(() -> mapping.complete(v2Claim, conflict))
                .hasMessage("MAPPING_RESULT_CONFLICT");
        long retryInput =
                seed(
                        "success",
                        null,
                        "Employer: Meadow Labs\nTitle: HR Manager\nDates: 2010 - 2012");
        jdbc.update(
                """
                INSERT INTO "CV_MAPPING_JOBS" ("EXTRACTION_ID","CONFIG_ID","ENGINE_VERSION")
                VALUES (?,?,'experience-mapping/2.0')
                """,
                retryInput,
                configId);
        var stale = mapping.claim().orElseThrow();
        var staleResult = mapping.process(stale);
        expire(stale);
        assertThatThrownBy(() -> mapping.complete(stale, staleResult))
                .hasMessage("MAPPING_STALE_ATTEMPT");
        assertThat(mapping.result(stale.jobId())).isEmpty();
    }

    @Test
    void personalV3ProcessesAndReplaysThroughActivatedLifecycle() throws Exception {
        com.cvcompass.backend.mapping.dto.PersonalInfoDefinition config;
        try (var stream = getClass().getResourceAsStream("/mapping/personal-info-config-v3.json")) {
            config =
                    (com.cvcompass.backend.mapping.dto.PersonalInfoDefinition)
                            com.cvcompass.backend.mapping.dto.MappingVersions.config(
                                    new String(
                                            stream.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8),
                                    json);
        }
        long id = mapping.register(config.revision(), config);
        long extraction =
                seed(
                        "success",
                        null,
                        "Full name: Ada Example\nEmail: ada@example.test\nGender: F\nExperience\nCompany: Example Workshop\nTitle: Engineer\nPeriod: 2019 - 2021");
        var source =
                jdbc.queryForMap("SELECT * FROM \"CV_EXTRACTIONS\" WHERE \"ID\"=?", extraction);
        mapping.activate(id);
        new com.cvcompass.backend.mapping.repository.MappingRepository(jdbc, json)
                .enqueue(
                        extraction,
                        id,
                        com.cvcompass.backend.mapping.service.PersonalInfoEngine.VERSION,
                        3);
        var claim = mapping.claim().orElseThrow();
        var result =
                (com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult)
                        mapping.process(claim);
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Ada Example");
        assertThat(result.personalInfo().get("gender").normalized()).isEqualTo("FEMALE");
        var input = inputs.find(extraction).orElseThrow();
        var experienceClaim =
                new MappingClaim(
                        claim.jobId(),
                        extraction,
                        id,
                        claim.configRevision(),
                        com.cvcompass.backend.mapping.service.ExperienceEngine.VERSION,
                        claim.attempt(),
                        claim.token(),
                        claim.leaseExpiresAt(),
                        config.experience());
        var v2 =
                new com.cvcompass.backend.mapping.service.ExperienceEngine(
                                new com.cvcompass.backend.mapping.service.MappingEngine())
                        .map(experienceClaim, input);
        assertThat(result.experiences()).isEqualTo(v2.experiences());
        assertThat(result.issues()).isEqualTo(v2.issues());
        mapping.complete(claim, result);
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?", claim.jobId());
        mapping.complete(claim, result);
        assertThat(mapping.result(claim.jobId())).contains(result);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                                claim.jobId()))
                .isEqualTo(row);
        assertThat(jdbc.queryForMap("SELECT * FROM \"CV_EXTRACTIONS\" WHERE \"ID\"=?", extraction))
                .isEqualTo(source);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CANDIDATE_PUBLICATIONS\"", Integer.class))
                .isZero();
    }

    @Test
    void optInGrammarJobsKeepTheirEngineAcrossActivationAndReplay() throws Exception {
        com.cvcompass.backend.mapping.dto.PersonalInfoDefinition legacy;
        com.cvcompass.backend.mapping.dto.PersonalInfoDefinition grammar;
        try (var input = getClass().getResourceAsStream("/mapping/personal-info-config-v3.json")) {
            legacy =
                    (com.cvcompass.backend.mapping.dto.PersonalInfoDefinition)
                            com.cvcompass.backend.mapping.dto.MappingVersions.config(
                                    new String(
                                            input.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8),
                                    json);
        }
        try (var input =
                getClass().getResourceAsStream("/mapping/personal-info-config-v3.1.json")) {
            grammar =
                    (com.cvcompass.backend.mapping.dto.PersonalInfoDefinition)
                            com.cvcompass.backend.mapping.dto.MappingVersions.config(
                                    new String(
                                            input.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8),
                                    json);
        }
        long oldId = mapping.register(legacy.revision(), legacy);
        long nextId = mapping.register(grammar.revision(), grammar);
        var repository = new com.cvcompass.backend.mapping.repository.MappingRepository(jdbc, json);
        long first = seed("success", null, "Full name: Legacy Example\nGender: M");
        repository.enqueue(first, oldId, "personal-info-mapping/3.0", 3);
        mapping.activate(nextId);
        var oldClaim = mapping.claim().orElseThrow();
        assertThat(oldClaim.engineVersion()).isEqualTo("personal-info-mapping/3.0");
        assertThat(oldClaim.configId()).isEqualTo(oldId);
        var oldResult = mapping.process(oldClaim);
        mapping.complete(oldClaim, oldResult);
        var oldRow =
                jdbc.queryForMap(
                        "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                        oldClaim.jobId());
        long second = seed("success", null, "Full name: Grammar Example\nGender: F");
        assertThatThrownBy(() -> repository.enqueue(second, nextId, "personal-info-mapping/3.0", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MAPPING_ENGINE_CONFIG_MISMATCH");
        repository.enqueue(second, nextId, "personal-info-mapping/3.1", 3);
        var nextClaim = mapping.claim().orElseThrow();
        assertThat(nextClaim.engineVersion()).isEqualTo("personal-info-mapping/3.1");
        assertThat(nextClaim.configId()).isEqualTo(nextId);
        var result =
                (com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult)
                        mapping.process(nextClaim);
        assertThat(result.engineVersion()).isEqualTo("personal-info-mapping/3.1");
        assertThat(result.configRevision()).isEqualTo(grammar.revision());
        assertThat(result.personalInfo().get("gender").normalized()).isEqualTo("FEMALE");
        mapping.complete(nextClaim, result);
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                        nextClaim.jobId());
        mapping.complete(nextClaim, result);
        assertThat(mapping.result(nextClaim.jobId())).contains(result);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                                nextClaim.jobId()))
                .isEqualTo(row);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                                oldClaim.jobId()))
                .isEqualTo(oldRow);
        assertThat(mapping.claim()).isEmpty();
    }

    @Test
    void v32ActivationRoutesNewJobsAndKeepsQueuedV31Pinned() throws Exception {
        var v31 = personalDefinition("3.1");
        var v32 = personalDefinition("3.2");
        long oldId = mapping.register(v31.revision(), v31);
        long nextId = mapping.register(v32.revision(), v32);
        long extraction = seed("success", null, "Location: Phường Mẫu\nMobile: +84(0)900000002");
        var repository = new MappingRepository(jdbc, json);
        repository.enqueue(extraction, oldId, v31.engineCompatibility(), 3);
        assertThatThrownBy(
                        () -> repository.enqueue(extraction, nextId, v31.engineCompatibility(), 3))
                .hasMessage("MAPPING_ENGINE_CONFIG_MISMATCH");

        mapping.activate(nextId);
        repository.cursor(extraction - 1, null);
        assertThat(mapping.scan(100)).isEqualTo(1);

        var oldClaim = mapping.claim().orElseThrow();
        assertThat(oldClaim.engineVersion()).isEqualTo(v31.engineCompatibility());
        assertThat(oldClaim.configId()).isEqualTo(oldId);
        var old = (PersonalInfoMappingResult) mapping.process(oldClaim);
        assertThat(old.personalInfo().get("phone").state()).isEqualTo("INVALID");
        mapping.complete(oldClaim, old);

        var nextClaim = mapping.claim().orElseThrow();
        assertThat(nextClaim.engineVersion()).isEqualTo(v32.engineCompatibility());
        assertThat(nextClaim.configId()).isEqualTo(nextId);
        var next = (PersonalInfoMappingResult) mapping.process(nextClaim);
        assertThat(next.personalInfo().get("phone").normalized()).isEqualTo("0900000002");
        assertThat(next.personalInfo().get("location").raw()).isEqualTo("Phường Mẫu");
        mapping.complete(nextClaim, next);
        mapping.complete(nextClaim, next);
        assertThat(mapping.result(nextClaim.jobId())).contains(next);
        assertThat(mapping.result(oldClaim.jobId())).contains(old);
    }

    private PersonalInfoDefinition personalDefinition(String version) throws Exception {
        try (var stream =
                getClass()
                        .getResourceAsStream(
                                "/mapping/personal-info-config-v" + version + ".json")) {
            return (PersonalInfoDefinition)
                    MappingVersions.config(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8), json);
        }
    }

    @Test
    void personalV3RegistersActivatesAndEnqueuesWithImmutableRevision() throws Exception {
        com.cvcompass.backend.mapping.dto.PersonalInfoDefinition config;
        try (var stream = getClass().getResourceAsStream("/mapping/personal-info-config-v3.json")) {
            config =
                    (com.cvcompass.backend.mapping.dto.PersonalInfoDefinition)
                            com.cvcompass.backend.mapping.dto.MappingVersions.config(
                                    new String(
                                            stream.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8),
                                    json);
        }
        var definition = config;
        long id = mapping.register(config.revision(), config);
        var repository = new com.cvcompass.backend.mapping.repository.MappingRepository(jdbc, json);
        assertThat(mapping.register(config.revision(), config)).isEqualTo(id);
        assertThatThrownBy(() -> mapping.register("different-revision", definition))
                .hasMessage("MAPPING_REVISION_MISMATCH");
        assertThatThrownBy(() -> mapping.scan(1)).hasMessage("MAPPING_ACTIVE_CONFIG_REQUIRED");
        mapping.activate(id);
        long extraction = seed();
        repository.enqueue(
                extraction,
                id,
                com.cvcompass.backend.mapping.dto.PersonalInfoDefinition.ENGINE_VERSION,
                3);
        assertThatThrownBy(() -> repository.enqueue(extraction, id, "literal-mapping/1.0", 3))
                .hasMessage("MAPPING_ENGINE_CONFIG_MISMATCH");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"=? AND \"CONFIG_ID\"=?",
                                Integer.class,
                                extraction,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"VALIDATION_STATE\" FROM \"CV_MAPPING_CONFIGS\" WHERE \"ID\"=?",
                                String.class,
                                id))
                .isEqualTo("ACTIVE");
        assertThat(mapping.scan(100)).isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_MAPPING_JOBS\" WHERE \"EXTRACTION_ID\"=? AND \"CONFIG_ID\"=?",
                                Integer.class,
                                extraction,
                                id))
                .isEqualTo(1);
        var file = java.nio.file.Files.createTempFile("synthetic-v3-", ".json");
        try {
            java.nio.file.Files.writeString(
                    file, com.cvcompass.backend.mapping.service.MappingDigests.canonical(config));
            new com.cvcompass.backend.mapping.config.MappingBootstrap(
                            mapping, json, file.toString(), definition.revision())
                    .run(new org.springframework.boot.DefaultApplicationArguments());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"ACTIVE_CONFIG_ID\" FROM \"CV_MAPPING_SETTINGS\" WHERE \"ID\"=1",
                                    Long.class))
                    .isEqualTo(id);
        } finally {
            java.nio.file.Files.delete(file);
        }
    }

    @Test
    void v3RevisionChangesRemainImmutableAcrossServiceRestart() throws Exception {
        ObjectNode node;
        try (var stream = getClass().getResourceAsStream("/mapping/personal-info-config-v3.json")) {
            node = (ObjectNode) json.readTree(stream);
        }
        var original =
                (com.cvcompass.backend.mapping.dto.PersonalInfoDefinition)
                        com.cvcompass.backend.mapping.dto.MappingVersions.config(
                                json.writeValueAsString(node), json);
        long originalId = mapping.register(original.revision(), original);
        var originalRow =
                jdbc.queryForMap("SELECT * FROM \"CV_MAPPING_CONFIGS\" WHERE \"ID\"=?", originalId);
        var restarted =
                new MappingService(
                        new com.cvcompass.backend.mapping.repository.MappingRepository(jdbc, json),
                        inputs,
                        new com.cvcompass.backend.mapping.service.MappingEngine(),
                        json,
                        transactions,
                        new com.cvcompass.backend.mapping.config.MappingProperties(),
                        new com.cvcompass.backend.mapping.service.ExperienceEngine(
                                new com.cvcompass.backend.mapping.service.MappingEngine()));
        assertThat(restarted.register(original.revision(), original)).isEqualTo(originalId);
        ((ObjectNode) node.path("personalFields").path("fullName"))
                .putArray("labels")
                .add("Synthetic name label");
        var changed =
                com.cvcompass.backend.mapping.dto.MappingVersions.config(
                        json.writeValueAsString(node), json);
        assertThatThrownBy(() -> restarted.register(original.revision(), changed))
                .isInstanceOf(DataAccessException.class);
        String newRevision = "synthetic-v3-restart-" + UUID.randomUUID();
        node.put("revision", newRevision);
        var next =
                com.cvcompass.backend.mapping.dto.MappingVersions.config(
                        json.writeValueAsString(node), json);
        long nextId = restarted.register(newRevision, next);
        assertThat(nextId).isNotEqualTo(originalId);
        assertThat(restarted.register(newRevision, next)).isEqualTo(nextId);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"CV_MAPPING_CONFIGS\" WHERE \"ID\"=?", originalId))
                .isEqualTo(originalRow);
        restarted.activate(nextId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ACTIVE_CONFIG_ID\" FROM \"CV_MAPPING_SETTINGS\" WHERE \"ID\"=1",
                                Long.class))
                .isEqualTo(nextId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_MAPPING_JOBS\"", Integer.class))
                .isZero();
    }

    @Test
    void migrationAllowsReadingV3WithoutClaimingOrRewritingOldJobs() throws Exception {
        long extraction = seed();
        var claim = queued(extraction, activate());
        var before = mapping.process(claim);
        mapping.complete(claim, before);
        var oldRow =
                jdbc.queryForMap(
                        "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?", claim.jobId());
        long anotherExtraction = seed();
        long otherJob =
                jdbc.queryForObject(
                        "INSERT INTO \"CV_MAPPING_JOBS\" (\"EXTRACTION_ID\",\"CONFIG_ID\",\"ENGINE_VERSION\") VALUES (?,?,'personal-info-mapping/3.0') RETURNING \"ID\"",
                        Long.class,
                        anotherExtraction,
                        claim.configId());
        var node =
                (ObjectNode)
                        json.readTree(
                                com.cvcompass.backend.mapping.service.MappingDigests.canonical(
                                        before));
        node.put("schemaVersion", 3).put("engineVersion", "personal-info-mapping/3.0");
        node.put("rulesRevision", "synthetic-rules").put("taxonomyRevision", "synthetic-taxonomy");
        node.putArray("experiences");
        node.putArray("issues");
        var personal = node.putObject("personalInfo");
        for (String name : com.cvcompass.backend.mapping.dto.PersonalInfoDefinition.FIELD_NAMES) {
            var field = personal.putObject(name);
            field.put("enabled", true)
                    .putNull("raw")
                    .putNull("normalized")
                    .put("state", "NOT_FOUND");
            field.putArray("alternatives");
            field.putArray("evidence");
            field.putArray("issues");
        }
        String body = json.writeValueAsString(node);
        jdbc.update(
                "INSERT INTO \"CV_MAPPING_RESULTS\" (\"JOB_ID\",\"SCHEMA_VERSION\",\"QUALITY\",\"DIGEST\",\"RESULT\") VALUES (?,3,'NEEDS_REVIEW',?,?::jsonb)",
                otherJob,
                com.cvcompass.backend.mapping.service.MappingDigests.sha256(body),
                body);
        assertThat(mapping.result(otherJob).orElseThrow())
                .isInstanceOf(com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.class);
        assertThat(mapping.claim()).isEmpty();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                                claim.jobId()))
                .isEqualTo(oldRow);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ENGINE_VERSION\" FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?",
                                String.class,
                                claim.jobId()))
                .isEqualTo("literal-mapping/1.0");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CV_MAPPING_RESULTS\" SET \"SCHEMA_VERSION\"=4 WHERE \"JOB_ID\"=?",
                                        otherJob))
                .isInstanceOf(DataAccessException.class);
    }
}
