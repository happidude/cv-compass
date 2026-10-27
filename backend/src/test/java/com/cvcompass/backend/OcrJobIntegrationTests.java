package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;

import com.cvcompass.backend.ingestion.dto.*;
import com.cvcompass.backend.ingestion.entity.*;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.exception.OcrStateException;
import com.cvcompass.backend.ingestion.repository.*;
import com.cvcompass.backend.ingestion.service.OcrJobService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cv.ocr.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OcrJobIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static final String HASH = "a".repeat(64);

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(OcrJobIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Autowired OcrJobService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired CvOcrAttemptRepository attempts;
    @Autowired CvExtractionRepository extractions;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void ownSchema() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(DATABASE.schemaName());
    }

    private CvProcessingJob seed() {
        var document = new CvDocument();
        document.setOriginalFilename("synthetic.pdf");
        document.setStoragePath(UUID.randomUUID().toString().replace("-", "") + ".pdf");
        document.setFileSize(1024L);
        document.setMimeType("application/pdf");
        document.setSha256(HASH);
        document.setStatus(DocumentStatus.PENDING);
        documents.saveAndFlush(document);
        var job = new CvProcessingJob();
        job.setDocument(document);
        job.setStatus(JobStatus.PENDING);
        job.setProfileRevision("synthetic-" + UUID.randomUUID());
        job.setGeneration(1);
        job.setMaxAttempts(3);
        job.setAttemptsStarted(0);
        job.setNextAttemptAt(Instant.parse("2026-01-01T00:00:00Z"));
        job.setAdmissionSnapshot(
                Map.of("synthetic_only", true, "sha256", HASH, "size_bytes", 1024));
        return jobs.saveAndFlush(job);
    }

    private OcrClaimRequest request(CvProcessingJob job) {
        return new OcrClaimRequest(
                "worker_" + UUID.randomUUID(), UUID.randomUUID(), HASH, job.getProfileRevision());
    }

    private OcrAttemptKey key(OcrClaim claim, OcrClaimRequest request) {
        return new OcrAttemptKey(claim.attemptId(), request.workerId(), HASH);
    }

    private long attemptId(OcrClaim claim) {
        return jdbc.queryForObject(
                "SELECT \"ID\" FROM \"CV_OCR_ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                Long.class,
                claim.attemptId());
    }

    private void stale(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        OcrStateException.class,
                        e -> assertThat(e.reason()).isEqualTo(OcrStateError.STALE_ATTEMPT));
    }

    private void expire(OcrClaim claim) {
        jdbc.update(
                """
            UPDATE "CV_OCR_ATTEMPTS" SET "STARTED_AT"=clock_timestamp()-interval '20 seconds',
            "LEASE_EXPIRES_AT"=clock_timestamp()-interval '10 seconds' WHERE "PUBLIC_ID"=?
            """,
                claim.attemptId());
    }

    private void releaseQuarantine(OcrClaim claim) {
        jdbc.update(
                """
            UPDATE "CV_OCR_ATTEMPTS" SET "STARTED_AT"=clock_timestamp()-interval '40 seconds',
            "LEASE_EXPIRES_AT"=clock_timestamp()-interval '30 seconds',
            "HARD_DEADLINE_AT"=clock_timestamp()-interval '20 seconds',
            "RECLAIM_AFTER"=clock_timestamp()-interval '10 seconds' WHERE "PUBLIC_ID"=?
            """,
                claim.attemptId());
        jdbc.update(
                "UPDATE \"CV_PROCESSING_JOBS\" SET \"NEXT_ATTEMPT_AT\"=clock_timestamp()-interval '1 second' WHERE \"ID\"=?",
                claim.jobId());
    }

    @Test
    void claimReplayHeartbeatAndTerminalResultAreIdempotent() {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        assertThat(service.claim(request).orElseThrow().attemptId()).isEqualTo(claim.attemptId());
        assertThat(service.heartbeat(key(claim, request)).leaseExpiresAt())
                .isAfterOrEqualTo(claim.leaseExpiresAt());
        String result = OcrFixtures.success(claim);
        var receipt = service.complete(key(claim, request), result);
        assertThat(receipt.replay()).isFalse();
        assertThat(receipt.extractionId()).isNotNull();
        assertThat(service.complete(key(claim, request), result).replay()).isTrue();
        assertThat(jobs.findById(job.getId()).orElseThrow().getEndReason())
                .isEqualTo(OcrEndReason.RESULT_SUCCESS);
        assertThat(documents.findById(claim.documentId()).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.COMPLETED);
        var extraction = extractions.findById(receipt.extractionId()).orElseThrow();
        assertThat(extraction.getOcrResult()).containsEntry("status", "success");
        assertThat(extraction.getRawText()).isNull();
        stale(() -> service.heartbeat(key(claim, request)));
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        key(claim, request),
                                        result.replace("Synthetic text", "Different text")))
                .isInstanceOfSatisfying(
                        OcrStateException.class,
                        e -> assertThat(e.reason()).isEqualTo(OcrStateError.OUTCOME_CONFLICT));
    }

    @Test
    void concurrentClaimOfOneJobHasExactlyOneWinner() throws Exception {
        var job = seed();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                gate.await();
                                return service.claim(request(job));
                            });
            var second =
                    executor.submit(
                            () -> {
                                gate.await();
                                return service.claim(request(job));
                            });
            gate.countDown();
            assertThat(
                            List.of(
                                            first.get(10, TimeUnit.SECONDS),
                                            second.get(10, TimeUnit.SECONDS))
                                    .stream()
                                    .filter(Optional::isPresent)
                                    .count())
                    .isEqualTo(1);
        }
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId())).hasSize(1);
    }

    @Test
    void concurrentSameKeyReplaysAndChangedProofConflicts() throws Exception {
        var job = seed();
        var request = request(job);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.claim(request));
            var second = executor.submit(() -> service.claim(request));
            assertThat(first.get(10, TimeUnit.SECONDS).orElseThrow().attemptId())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).orElseThrow().attemptId());
        }
        assertThatThrownBy(
                        () ->
                                service.claim(
                                        new OcrClaimRequest(
                                                request.workerId(),
                                                request.claimRequestId(),
                                                "b".repeat(64),
                                                request.profileRevision())))
                .isInstanceOfSatisfying(
                        OcrStateException.class,
                        e -> assertThat(e.reason()).isEqualTo(OcrStateError.CLAIM_CONFLICT));
    }

    @Test
    void expiredLeaseRejectsResultsAndQuarantinesRetry() {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        expire(claim);
        stale(() -> service.complete(key(claim, request), OcrFixtures.success(claim)));
        service.expireDue();
        var updated = jobs.findById(job.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(updated.getNextAttemptAt()).isAfterOrEqualTo(claim.hardDeadlineAt());
        assertThat(service.claim(request(job))).isEmpty();
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId()).getFirst().getStatus())
                .isEqualTo(OcrAttemptStatus.EXPIRED);
        releaseQuarantine(claim);
        var retry = service.claim(request(job)).orElseThrow();
        assertThat(retry.attemptNo()).isEqualTo(2);
        stale(() -> service.heartbeat(key(claim, request)));
        stale(() -> service.claim(request));
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId())).hasSize(2);
    }

    @Test
    void boundedInfraRetriesKeepAttemptHistoryAndTerminationReason() {
        var job = seed();
        for (int number = 1; number <= 3; number++) {
            var request = request(job);
            var claim = service.claim(request).orElseThrow();
            assertThat(claim.attemptNo()).isEqualTo(number);
            service.fail(key(claim, request), OcrFailureCode.SUPERVISOR_TIMEOUT);
            assertThat(
                            service.fail(key(claim, request), OcrFailureCode.SUPERVISOR_TIMEOUT)
                                    .replay())
                    .isTrue();
            if (number < 3) {
                assertThat(service.claim(request(job))).isEmpty();
                releaseQuarantine(claim);
            }
        }
        var updated = jobs.findById(job.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(updated.getEndReason()).isEqualTo(OcrEndReason.RETRIES_EXHAUSTED);
        assertThat(updated.getErrorCode()).isEqualTo("SUPERVISOR_TIMEOUT");
        assertThat(updated.getRetryCount()).isEqualTo(2);
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId())).hasSize(3);
        assertThat(service.claim(request(job))).isEmpty();
    }

    @Test
    void configurationFailureIsTerminalWithoutRetry() {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        service.fail(key(claim, request), OcrFailureCode.CONFIGURATION_ERROR);
        var updated = jobs.findById(job.getId()).orElseThrow();
        assertThat(updated.getEndReason()).isEqualTo(OcrEndReason.CONFIGURATION_ERROR);
        assertThat(updated.getAttemptsStarted()).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void deliveredConfigurationErrorKeepsOriginalResultAndPreciseReason() {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        var json = JsonMapper.builder().build();
        var envelope = (ObjectNode) json.readTree(OcrFixtures.success(claim));
        var result = (ObjectNode) envelope.get("ocr_result");
        result.put("status", "failed").putNull("page_count");
        result.withArray("pages").removeAll();
        result.withArray("errors")
                .addObject()
                .put("code", "MODEL_MISSING")
                .put("stage", "preflight");
        envelope.put("exit_code", 2);
        var receipt = service.complete(key(claim, request), envelope.toString());
        var updated = jobs.findById(job.getId()).orElseThrow();
        assertThat(updated.getEndReason()).isEqualTo(OcrEndReason.CONFIGURATION_ERROR);
        assertThat(updated.getErrorCode()).isEqualTo("MODEL_MISSING");
        assertThat(updated.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(extractions.findById(receipt.extractionId()).orElseThrow().getOcrResult())
                .containsEntry("status", "failed");
        assertThat(service.claim(request(job))).isEmpty();
    }

    @Test
    void activeDocumentAndAcceptedExtractionStayUniqueAcrossProfiles() {
        var job = seed();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
            INSERT INTO "CV_PROCESSING_JOBS" ("CV_DOCUMENT_ID","STATUS","PROFILE_REVISION","GENERATION","MAX_ATTEMPTS","ATTEMPTS_STARTED","NEXT_ATTEMPT_AT","ADMISSION_SNAPSHOT")
            VALUES (?,'PENDING','different-profile',1,3,0,clock_timestamp(),'{}'::jsonb)
            """,
                                        job.getDocument().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        service.complete(key(claim, request), OcrFixtures.success(claim));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
            INSERT INTO "CV_EXTRACTIONS" ("CV_DOCUMENT_ID","PROCESSING_JOB_ID","ATTEMPT_ID","EXTRACTION_METHOD","OCR_RESULT","OCR_STATUS","PROFILE_REVISION","PROVENANCE")
            SELECT "CV_DOCUMENT_ID","PROCESSING_JOB_ID","ATTEMPT_ID","EXTRACTION_METHOD","OCR_RESULT","OCR_STATUS","PROFILE_REVISION","PROVENANCE"
            FROM "CV_EXTRACTIONS" WHERE "PROCESSING_JOB_ID"=?
            """,
                                        job.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId())).hasSize(1);
    }

    @Test
    void wrongProofIdentityAndFailureAfterAcceptedCannotWrite() {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        stale(
                () ->
                        service.heartbeat(
                                new OcrAttemptKey(
                                        claim.attemptId(), request.workerId(), "b".repeat(64))));
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        key(claim, request),
                                        OcrFixtures.success(claim)
                                                .replace(
                                                        "\"document_id\":\""
                                                                + claim.documentId()
                                                                + "\"",
                                                        "\"document_id\":\"999999\"")))
                .isInstanceOfSatisfying(
                        OcrStateException.class,
                        e -> assertThat(e.reason()).isEqualTo(OcrStateError.INVALID_RESULT));
        service.complete(key(claim, request), OcrFixtures.success(claim));
        assertThatThrownBy(
                        () -> service.fail(key(claim, request), OcrFailureCode.CONFIGURATION_ERROR))
                .isInstanceOfSatisfying(
                        OcrStateException.class,
                        e -> assertThat(e.reason()).isEqualTo(OcrStateError.OUTCOME_CONFLICT));
    }

    @Test
    void concurrentExpiryAndSubmissionHaveOneAtomicOutcome() throws Exception {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        expire(claim);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(service::expireDue);
            var second =
                    executor.submit(
                            () -> {
                                stale(
                                        () ->
                                                service.complete(
                                                        key(claim, request),
                                                        OcrFixtures.success(claim)));
                                return true;
                            });
            first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        // A sweep can skip the submission's job lock; the next scheduler tick must reclaim it.
        service.expireDue();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PENDING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_EXTRACTIONS\" WHERE \"PROCESSING_JOB_ID\"=?",
                                Integer.class,
                                job.getId()))
                .isZero();
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId()))
                .singleElement()
                .satisfies(
                        attempt ->
                                assertThat(attempt.getStatus())
                                        .isEqualTo(OcrAttemptStatus.EXPIRED));
    }

    @Test
    void expirySkippedDuringSubmissionIsRecoveredOnNextSweep() throws Exception {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        expire(claim);
        var unlockedJob = seed();
        var unlockedClaim = service.claim(request(unlockedJob)).orElseThrow();
        expire(unlockedClaim);
        var submissionLocked = new CountDownLatch(1);
        var sweepFinished = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var submission =
                    executor.submit(
                            () -> {
                                stale(
                                        () ->
                                                new TransactionTemplate(transactions)
                                                        .executeWithoutResult(
                                                                tx -> {
                                                                    jdbc.queryForObject(
                                                                            "SELECT \"ID\" FROM \"CV_PROCESSING_JOBS\" WHERE \"ID\"=? FOR UPDATE",
                                                                            Long.class,
                                                                            job.getId());
                                                                    submissionLocked.countDown();
                                                                    awaitSignal(sweepFinished);
                                                                    service.complete(
                                                                            key(claim, request),
                                                                            OcrFixtures.success(
                                                                                    claim));
                                                                }));
                                return true;
                            });
            try {
                assertThat(submissionLocked.await(10, TimeUnit.SECONDS))
                        .as("submission holds job lock")
                        .isTrue();
                service.expireDue();
                assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                        .isEqualTo(JobStatus.PROCESSING);
                assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId()))
                        .singleElement()
                        .satisfies(
                                attempt ->
                                        assertThat(attempt.getStatus())
                                                .isEqualTo(OcrAttemptStatus.ACTIVE));
                // A locked submission must not block expiry of other jobs in the same sweep.
                assertThat(jobs.findById(unlockedJob.getId()).orElseThrow().getStatus())
                        .isEqualTo(JobStatus.PENDING);
                assertThat(attempts.findByJobIdOrderByAttemptNoAsc(unlockedJob.getId()))
                        .singleElement()
                        .satisfies(
                                attempt ->
                                        assertThat(attempt.getStatus())
                                                .isEqualTo(OcrAttemptStatus.EXPIRED));
            } finally {
                sweepFinished.countDown();
            }
            assertThat(submission.get(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PROCESSING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_EXTRACTIONS\" WHERE \"PROCESSING_JOB_ID\"=?",
                                Integer.class,
                                job.getId()))
                .isZero();
        service.expireDue();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PENDING);
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId()))
                .singleElement()
                .satisfies(
                        attempt -> {
                            assertThat(attempt.getStatus()).isEqualTo(OcrAttemptStatus.EXPIRED);
                            assertThat(attempt.getEndReason())
                                    .isEqualTo(OcrEndReason.LEASE_EXPIRED);
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_EXTRACTIONS\" WHERE \"PROCESSING_JOB_ID\"=?",
                                Integer.class,
                                job.getId()))
                .isZero();
    }

    private static void awaitSignal(CountDownLatch signal) {
        try {
            assertThat(signal.await(10, TimeUnit.SECONDS)).as("concurrent test signal").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test interrupted", interrupted);
        }
    }

    @Test
    void partialMixedAndFailedNoneResultsAreTerminal() {
        for (boolean partial : List.of(true, false)) {
            var job = seed();
            var request = request(job);
            var claim = service.claim(request).orElseThrow();
            var json = JsonMapper.builder().build();
            var envelope = (ObjectNode) json.readTree(OcrFixtures.success(claim));
            var result = (ObjectNode) envelope.get("ocr_result");
            var pages = result.withArray("pages");
            if (partial) {
                var page = pages.get(0).deepCopy();
                ((ObjectNode) page).put("page_number", 2).put("method", "ocr");
                pages.add(page);
                result.put("page_count", 2).put("status", "partial");
                envelope.put("exit_code", 1);
                result.withArray("errors")
                        .addObject()
                        .put("code", "SUMMARY_PARTIAL")
                        .put("stage", "supervisor");
            } else {
                var page = (ObjectNode) pages.get(0);
                page.put("status", "blank")
                        .put("method", "none")
                        .put("text", "")
                        .put("raw_text", "");
                page.withArray("warnings").add("BLANK_PAGE");
                result.put("status", "failed");
                envelope.put("exit_code", 2);
            }
            var receipt = service.complete(key(claim, request), envelope.toString());
            assertThat(
                            extractions
                                    .findById(receipt.extractionId())
                                    .orElseThrow()
                                    .getExtractionMethod())
                    .isEqualTo(partial ? ExtractionMethod.MIXED : ExtractionMethod.NONE);
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                    .isEqualTo(partial ? JobStatus.PARTIAL : JobStatus.FAILED);
            assertThat(service.claim(request(job))).isEmpty();
        }
    }

    @Test
    void rollbackRestoresJobAttemptAndResultAtomically() {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                tx -> {
                                                    service.complete(
                                                            key(claim, request),
                                                            OcrFixtures.success(claim));
                                                    throw new IllegalStateException(
                                                            "synthetic rollback");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PROCESSING);
        assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId()).getFirst().getStatus())
                .isEqualTo(OcrAttemptStatus.ACTIVE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_EXTRACTIONS\" WHERE \"PROCESSING_JOB_ID\"=?",
                                Integer.class,
                                job.getId()))
                .isZero();
    }

    @Test
    void compositeForeignKeysAndJsonConstraintsCannotBeBypassed() {
        var first = seed();
        var second = seed();
        var request = request(first);
        var claim = service.claim(request).orElseThrow();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CV_PROCESSING_JOBS\" SET \"CURRENT_ATTEMPT_ID\"=? WHERE \"ID\"=?",
                                        attemptId(claim),
                                        second.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String result :
                List.of(
                        "{}",
                        "{\"status\":null,\"pages\":[]}",
                        "{\"status\":\"success\",\"pages\":null}")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            """
                INSERT INTO "CV_EXTRACTIONS" ("CV_DOCUMENT_ID","PROCESSING_JOB_ID","ATTEMPT_ID","EXTRACTION_METHOD","OCR_RESULT","OCR_STATUS","PROFILE_REVISION","PROVENANCE")
                VALUES (?,?,?,'NONE',?::jsonb,'success',?,'{}'::jsonb)
                """,
                                            claim.documentId(),
                                            claim.jobId(),
                                            attemptId(claim),
                                            result,
                                            claim.profileRevision()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CV_PROCESSING_JOBS\" SET \"MAX_ATTEMPTS\"=4 WHERE \"ID\"=?",
                                        first.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CV_OCR_ATTEMPTS\" SET \"STATUS\"='EXPIRED',\"FINISHED_AT\"=clock_timestamp(),\"END_REASON\"=NULL WHERE \"ID\"=?",
                                        attemptId(claim)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leaseCheckUsesWallClockAfterBlockedJobLock() throws Exception {
        var job = seed();
        var request = request(job);
        var claim = service.claim(request).orElseThrow();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> waiting =
                    new TransactionTemplate(transactions)
                            .execute(
                                    tx -> {
                                        jdbc.queryForObject(
                                                "SELECT \"ID\" FROM \"CV_PROCESSING_JOBS\" WHERE \"ID\"=? FOR UPDATE",
                                                Long.class,
                                                job.getId());
                                        var future =
                                                executor.submit(
                                                        () -> {
                                                            stale(
                                                                    () ->
                                                                            service.heartbeat(
                                                                                    key(
                                                                                            claim,
                                                                                            request)));
                                                            return true;
                                                        });
                                        long deadline =
                                                System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                                        boolean blocked = false;
                                        while (System.nanoTime() < deadline) {
                                            // PostgreSQL caches statistics inside a transaction;
                                            // refresh before observing the waiter.
                                            jdbc.execute("SELECT pg_stat_clear_snapshot()");
                                            int count =
                                                    jdbc.queryForObject(
                                                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%CV_PROCESSING_JOBS%'",
                                                            Integer.class);
                                            if (count > 0) {
                                                blocked = true;
                                                break;
                                            }
                                            java.util.concurrent.locks.LockSupport.parkNanos(
                                                    TimeUnit.MILLISECONDS.toNanos(10));
                                        }
                                        assertThat(blocked)
                                                .as(
                                                        "heartbeat is actually waiting for job row lock")
                                                .isTrue();
                                        jdbc.update(
                                                "UPDATE \"CV_OCR_ATTEMPTS\" SET \"LEASE_EXPIRES_AT\"=clock_timestamp()+interval '300 milliseconds' WHERE \"PUBLIC_ID\"=?",
                                                claim.attemptId());
                                        jdbc.execute("SELECT pg_sleep(0.6)");
                                        return future;
                                    });
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.findByJobIdOrderByAttemptNoAsc(job.getId()).getFirst().getStatus())
                    .isEqualTo(OcrAttemptStatus.ACTIVE);
        }
    }
}
