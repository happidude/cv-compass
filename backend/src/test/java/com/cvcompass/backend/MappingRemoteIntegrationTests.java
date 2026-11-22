package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cvcompass.backend.ingestion.dto.OcrAttemptKey;
import com.cvcompass.backend.ingestion.dto.OcrClaimRequest;
import com.cvcompass.backend.ingestion.entity.CvDocument;
import com.cvcompass.backend.ingestion.entity.CvProcessingJob;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.service.OcrJobService;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.config.MappingWorkerAuthenticationFilter;
import com.cvcompass.backend.mapping.dto.LlmExtractionDefinition;
import com.cvcompass.backend.mapping.remote.MappingRemoteException;
import com.cvcompass.backend.mapping.service.MappingDigests;
import com.cvcompass.backend.mapping.service.MappingRemoteFixtures;
import com.cvcompass.backend.mapping.service.MappingRemoteService;
import com.cvcompass.backend.mapping.service.MappingService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        properties = {
            "cv.ocr.enabled=true",
            "cv.mapping.remote.enabled=true",
            "cv.candidate.publication.enabled=false"
        })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class MappingRemoteIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static final String HASH = MappingDigests.sha256("synthetic-lease-token-only");
    @Autowired MappingService mapping;
    @Autowired MappingRemoteService remote;
    @Autowired OcrJobService ocr;
    @Autowired JdbcTemplate jdbc;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository ocrJobs;
    @Autowired ObjectMapper json;
    @Autowired CompletedMappings completed;
    @Autowired MappingRemoteProperties properties;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.cvcompass.backend.candidate.service.PublicationService publication;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(MappingRemoteIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(DATABASE.databaseName());
        jdbc.execute(
                "TRUNCATE \"CANDIDATE\".\"PROPOSALS\",\"CANDIDATE\".\"PUBLICATIONS\",\"CANDIDATE\".\"EXPERIENCES\",\"MAPPING\".\"RESULTS\",\"MAPPING\".\"ATTEMPTS\",\"MAPPING\".\"JOBS\"");
        properties.setEnabled(true);
    }

    private long seed() {
        return seed(null, MappingRemoteFixtures.definition());
    }

    private long seed(CvDocument existing, LlmExtractionDefinition definition) {
        var doc = existing == null ? new CvDocument() : existing;
        if (existing == null) {
            doc.setOriginalFilename("synthetic.pdf");
            doc.setStoragePath(UUID.randomUUID() + ".pdf");
            doc.setFileSize(1024L);
            doc.setMimeType("application/pdf");
            doc.setSha256("a".repeat(64));
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
        ocrJobs.saveAndFlush(job);
        var request =
                new OcrClaimRequest(
                        "synthetic_worker", UUID.randomUUID(), HASH, job.getProfileRevision());
        var claim = ocr.claim(request).orElseThrow();
        var envelope = (ObjectNode) json.readTree(OcrFixtures.success(claim));
        var result = (ObjectNode) envelope.path("ocr_result");
        result.put("schema_version", "cv-compass.ocr/2.0");
        var page = (ObjectNode) result.path("pages").get(0);
        page.remove("text");
        page.put("raw_text", MappingRemoteFixtures.RAW);
        long extraction =
                ocr.complete(
                                new OcrAttemptKey(claim.attemptId(), request.workerId(), HASH),
                                envelope.toString())
                        .extractionId();
        long config = mapping.register(definition.revision(), definition);
        return jdbc.queryForObject(
                """
                INSERT INTO "MAPPING"."JOBS" ("EXTRACTION_ID","CONFIG_ID","ENGINE_VERSION","MAX_ATTEMPTS")
                VALUES (?,?,'llm-extraction/1.0',5) RETURNING "ID"
                """,
                Long.class,
                extraction,
                config);
    }

    @Test
    void remoteHttpPublicationMatchesFrontendFixturesForInitialAndExistingProfile()
            throws Exception {
        String credential = "synthetic-mapping-token-" + "m".repeat(43);
        String lease = "l".repeat(43);
        var worker = new MappingRemoteProperties.Worker();
        worker.setCredentialSha256(Set.of(MappingWorkerAuthenticationFilter.hash(credential)));
        properties.setWorkers(Map.of("synthetic_worker", worker));
        jdbc.update(
                "INSERT INTO \"IDENTITY\".\"USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES (?,?,'USER') ON CONFLICT DO NOTHING",
                "synthetic-user",
                new BCryptPasswordEncoder().encode("synthetic-test-only"));
        var csrf =
                mvc.perform(get("/api/auth/csrf"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var login =
                mvc.perform(
                                post("/api/auth/login")
                                        .cookie(csrf.getCookie("XSRF-TOKEN"))
                                        .header(
                                                "X-XSRF-TOKEN",
                                                json.readTree(csrf.getContentAsString())
                                                        .path("token")
                                                        .asString())
                                        .param("username", "synthetic-user")
                                        .param("password", "synthetic-test-only"))
                        .andExpect(status().isNoContent())
                        .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        CvDocument doc = null;
        for (boolean subsequent : java.util.List.of(false, true)) {
            long job = seed(doc, LlmRemoteContractFixtures.definition());
            long documentId =
                    jdbc.queryForObject(
                            "SELECT e.\"CV_DOCUMENT_ID\" FROM \"INGESTION\".\"EXTRACTIONS\" e JOIN \"MAPPING\".\"JOBS\" j ON j.\"EXTRACTION_ID\"=e.\"ID\" WHERE j.\"ID\"=?",
                            Long.class,
                            job);
            doc = documents.findById(documentId).orElseThrow();
            jdbc.update(
                    "UPDATE \"INGESTION\".\"DOCUMENTS\" SET \"CREATED_AT\"=?::timestamptz WHERE \"ID\"=?",
                    LlmRemoteContractFixtures.AT,
                    documentId);
            var claimed =
                    mvc.perform(
                                    post("/internal/mapping/v1/claims")
                                            .header("Authorization", "Bearer " + credential)
                                            .header("X-Lease-Token", lease)
                                            .header("Idempotency-Key", UUID.randomUUID().toString())
                                            .contentType("application/json")
                                            .content(MappingRemoteFixtures.claimBody().toString()))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            var data = json.readTree(claimed.getContentAsString()).path("data");
            var body =
                    LlmRemoteContractFixtures.compact(
                            data.path("inputSha256").asString(), subsequent);
            mvc.perform(
                            put("/internal/mapping/v1/attempts/"
                                            + data.path("attemptId").asString()
                                            + "/result")
                                    .header("Authorization", "Bearer " + credential)
                                    .header("X-Lease-Token", lease)
                                    .contentType("application/json")
                                    .content(body.toString()))
                    .andExpect(status().isOk());
            long result =
                    jdbc.queryForObject(
                            "SELECT \"ID\" FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                            Long.class,
                            job);
            assertThat(publication.publish(result)).isTrue();
            long candidate =
                    jdbc.queryForObject(
                            "SELECT \"CANDIDATE_ID\" FROM \"INGESTION\".\"DOCUMENTS\" WHERE \"ID\"=?",
                            Long.class,
                            documentId);
            String prefix = subsequent ? "subsequent-" : "initial-";
            for (String endpoint : java.util.List.of("profile", "proposals", "history")) {
                var response =
                        mvc.perform(
                                        get("/api/candidates/"
                                                        + candidate
                                                        + (endpoint.equals("profile")
                                                                ? ""
                                                                : "/" + endpoint))
                                                .session(session))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse();
                var payload = json.readTree(response.getContentAsString());
                if (endpoint.equals("proposals"))
                    assertThat(payload.size()).isEqualTo(subsequent ? 6 : 0);
                if (endpoint.equals("profile"))
                    assertThat(payload.path("experiences").size()).isEqualTo(5);
                LlmRemoteContractFixtures.assertFixture(prefix + endpoint, payload);
            }
        }
    }

    private MappingRemoteService.Reply claim(UUID request) {
        return remote.claim(
                "synthetic_worker", request, HASH, MappingRemoteFixtures.claimBody().toString());
    }

    private UUID id(MappingRemoteService.Reply reply) {
        return (UUID) reply.data().get("attemptId");
    }

    private String result(MappingRemoteService.Reply reply) {
        return MappingRemoteFixtures.result((String) reply.data().get("inputSha256")).toString();
    }

    @Test
    void emptyClaimAndPinnedIdempotentClaimExcludeInProcessWorker() {
        assertThat(claim(UUID.randomUUID()).status()).isEqualTo(204);
        seed();
        assertThat(mapping.claim()).isEmpty();
        UUID request = UUID.randomUUID();
        var first = claim(request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.data())
                .containsKeys(
                        "attemptId",
                        "engineVersion",
                        "inputSha256",
                        "leaseExpiresAt",
                        "hardDeadlineAt",
                        "heartbeatInterval",
                        "definition");
        var replay = claim(request);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.data()).isEqualTo(first.data());
        String changed =
                MappingRemoteFixtures.claimBody()
                        .toString()
                        .replace("synthetic-model", "other-model");
        assertThatThrownBy(() -> remote.claim("synthetic_worker", request, HASH, changed))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(claim(UUID.randomUUID()).status()).isEqualTo(204);
    }

    @Test
    void overlappingClaimsSkipLockedJobAndClaimTwoDistinctJobs() throws Exception {
        long firstJob = seed();
        long secondJob = seed();
        var replies = overlappingClaims();
        assertThat(replies)
                .extracting(MappingRemoteService.Reply::status)
                .containsExactly(201, 201);
        assertThat(id(replies.get(0))).isNotEqualTo(id(replies.get(1)));
        var claimedJobs =
                replies.stream()
                        .map(
                                reply ->
                                        jdbc.queryForObject(
                                                "SELECT \"JOB_ID\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                                Long.class,
                                                id(reply)))
                        .toList();
        assertThat(claimedJobs).containsExactlyInAnyOrder(firstJob, secondJob);
    }

    @Test
    void overlappingClaimsWithOneJobReturnOneClaimAndOneNoWork() throws Exception {
        long job = seed();
        var replies = overlappingClaims();
        assertThat(replies)
                .extracting(MappingRemoteService.Reply::status)
                .containsExactlyInAnyOrder(201, 204);
        var claimed =
                replies.stream().filter(reply -> reply.status() == 201).findFirst().orElseThrow();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"JOB_ID\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                Long.class,
                                id(claimed)))
                .isEqualTo(job);
        var idle =
                replies.stream().filter(reply -> reply.status() == 204).findFirst().orElseThrow();
        assertThat(idle.data()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"STATUS\"='ACTIVE'",
                                Integer.class))
                .isEqualTo(1);
    }

    private List<MappingRemoteService.Reply> overlappingClaims() throws Exception {
        var connected = new CyclicBarrier(2);
        var claimed = new CyclicBarrier(2);
        var connections = ConcurrentHashMap.<Integer>newKeySet();
        Callable<MappingRemoteService.Reply> task =
                () ->
                        new TransactionTemplate(transactions)
                                .execute(
                                        status -> {
                                            connections.add(
                                                    jdbc.queryForObject(
                                                            "SELECT pg_backend_pid()",
                                                            Integer.class));
                                            awaitClaimBarrier(connected);
                                            var reply = claim(UUID.randomUUID());
                                            // Keep the claimed row locked until both service calls
                                            // return.
                                            awaitClaimBarrier(claimed);
                                            return reply;
                                        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(task);
            var second = executor.submit(task);
            var replies =
                    List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(connections).hasSize(2);
            return replies;
        }
    }

    private void awaitClaimBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent claim interrupted", error);
        } catch (Exception error) {
            throw new AssertionError("Concurrent claim barrier failed", error);
        }
    }

    @Test
    void simultaneousClaimsProduceExactlyOneActiveAttempt() throws Exception {
        seed();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                barrier.await();
                                return claim(UUID.randomUUID()).status();
                            });
            var b =
                    executor.submit(
                            () -> {
                                barrier.await();
                                return claim(UUID.randomUUID()).status();
                            });
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 204);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"STATUS\"='ACTIVE'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void resultStoresV3AndReplaysWithoutAllowingConflictingOutcome(CapturedOutput output) {
        long job = seed();
        UUID request = UUID.randomUUID();
        var claim = claim(request);
        String body = result(claim);
        assertThat(remote.input("synthetic_worker", id(claim), HASH)).containsKey("pages");
        assertThatThrownBy(() -> remote.input("synthetic_worker", id(claim), "b".repeat(64)))
                .hasMessage("INVALID_LEASE_TOKEN");
        assertThat(remote.heartbeat("synthetic_worker", id(claim), HASH, "{}"))
                .containsKey("leaseExpiresAt");
        remote.result("synthetic_worker", id(claim), HASH, body);
        remote.result("synthetic_worker", id(claim), HASH, body);
        assertThatThrownBy(() -> remote.result("synthetic_worker", id(claim), HASH, body + " "))
                .hasMessage("OUTCOME_CONFLICT");
        assertThat(mapping.result(job).orElseThrow().schemaVersion()).isEqualTo(3);
        assertThat(mapping.result(job).orElseThrow().quality()).isEqualTo("NEEDS_REVIEW");
        long resultId =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                        Long.class,
                        job);
        assertThat(completed.publishable(completed.read(resultId).orElseThrow())).isPresent();
        assertThat(claim(request).data().get("status")).isEqualTo("ACCEPTED");
        assertThat(output.getAll())
                .doesNotContain(MappingRemoteFixtures.RAW, "Synthetic Person", HASH);
    }

    @Test
    void fullDateOfBirthResultIsStoredAndPublishable() {
        long job = seed();
        var claim = claim(UUID.randomUUID());
        var body = MappingRemoteFixtures.result((String) claim.data().get("inputSha256"));
        ((ObjectNode) body.path("personal").path("dateOfBirth")).put("value", "1990-05-15");
        remote.result("synthetic_worker", id(claim), HASH, body.toString());
        long resultId =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                        Long.class,
                        job);
        assertThat(completed.publishable(completed.read(resultId).orElseThrow())).isPresent();
        assertThat(publication.publish(resultId)).isTrue();
    }

    @Test
    void multiSpanSummaryIsStoredAndPublishedWithAllEvidence() {
        long job = seed();
        var claim = claim(UUID.randomUUID());
        var body = MappingRemoteFixtures.result((String) claim.data().get("inputSha256"));
        String value = "Synthetic Person Synthetic Reviewer";
        var summary = ((ObjectNode) body.path("personal")).putObject("summary");
        summary.put("value", value);
        var spans = summary.putArray("evidence");
        spans.add(MappingRemoteFixtures.evidence("Synthetic Person"));
        spans.add(MappingRemoteFixtures.evidence("Synthetic Reviewer"));
        remote.result("synthetic_worker", id(claim), HASH, body.toString());
        long resultId =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                        Long.class,
                        job);
        var stored = completed.read(resultId).orElseThrow();
        var persisted = json.readTree(stored.body()).path("personalInfo").path("summary");
        var expectedSpans = spans.deepCopy();
        for (var span : expectedSpans)
            ((ObjectNode) span).put("extractionId", stored.extractionId());
        var expected = json.readTree(expectedSpans.toString());
        assertThat(persisted.path("raw").asString()).isEqualTo(value);
        assertThat(persisted.path("evidence")).isEqualTo(expected);
        assertThat(completed.publishable(stored)).isPresent();
        assertThat(publication.publish(resultId)).isTrue();
        var profile =
                jdbc.queryForMap(
                        """
                        SELECT p."BASIC"::text, p."EXTENSIONS"::text, c."STATUS"
                        FROM "CANDIDATE"."PROFILES" p
                        JOIN "CANDIDATE"."PUBLICATIONS" c ON c."CANDIDATE_ID"=p."CANDIDATE_ID"
                        WHERE c."RESULT_ID"=?
                        """,
                        resultId);
        assertThat(profile.get("STATUS")).isEqualTo("PUBLISHED");
        assertThat(json.readTree((String) profile.get("BASIC")).path("summary").asString())
                .isEqualTo(value);
        assertThat(
                        json.readTree((String) profile.get("EXTENSIONS"))
                                .path("personalInfo")
                                .path("fields")
                                .path("summary")
                                .path("evidence"))
                .isEqualTo(expected);
    }

    @Test
    void honorificFullNameIsStoredAndPublishedWithOriginalRawAndEvidence() {
        long job = seed();
        String value = "Mr Cao";
        jdbc.update(
                """
                UPDATE "INGESTION"."EXTRACTIONS"
                SET "OCR_RESULT"=jsonb_set("OCR_RESULT",'{pages,0,raw_text}',to_jsonb(?::text))
                WHERE "ID"=(SELECT "EXTRACTION_ID" FROM "MAPPING"."JOBS" WHERE "ID"=?)
                """,
                MappingRemoteFixtures.RAW + "\n" + value,
                job);
        var claim = claim(UUID.randomUUID());
        var body = MappingRemoteFixtures.result((String) claim.data().get("inputSha256"));
        int start =
                MappingRemoteFixtures.RAW.codePointCount(0, MappingRemoteFixtures.RAW.length()) + 1;
        var evidence =
                json.createObjectNode()
                        .put("page", 1)
                        .put("start", start)
                        .put("end", start + value.length())
                        .put("text", value);
        ((ObjectNode) body.path("personal"))
                .putObject("fullName")
                .put("value", value)
                .set("evidence", evidence);
        remote.result("synthetic_worker", id(claim), HASH, body.toString());
        long resultId =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                        Long.class,
                        job);
        var stored = completed.read(resultId).orElseThrow();
        var persisted = json.readTree(stored.body()).path("personalInfo").path("fullName");
        var expected =
                json.createArrayNode()
                        .add(evidence.deepCopy().put("extractionId", stored.extractionId()));
        assertThat(persisted.path("raw").asString()).isEqualTo(value);
        assertThat(persisted.path("normalized").asString()).isEqualTo("Cao");
        assertThat(persisted.path("state").asString()).isEqualTo("PROPOSED");
        assertThat(persisted.path("issues").isEmpty()).isTrue();
        assertThat(persisted.path("evidence")).isEqualTo(json.readTree(expected.toString()));
        assertThat(completed.publishable(stored)).isPresent();
        assertThat(publication.publish(resultId)).isTrue();
        var profile =
                jdbc.queryForMap(
                        """
                        SELECT p."BASIC"::text, p."EXTENSIONS"::text, c."STATUS"
                        FROM "CANDIDATE"."PROFILES" p
                        JOIN "CANDIDATE"."PUBLICATIONS" c ON c."CANDIDATE_ID"=p."CANDIDATE_ID"
                        WHERE c."RESULT_ID"=?
                        """,
                        resultId);
        assertThat(profile.get("STATUS")).isEqualTo("PUBLISHED");
        assertThat(json.readTree((String) profile.get("BASIC")).path("fullName").asString())
                .isEqualTo(value);
        var metadata =
                json.readTree((String) profile.get("EXTENSIONS"))
                        .path("personalInfo")
                        .path("fields")
                        .path("fullName");
        assertThat(metadata.path("raw").asString()).isEqualTo(value);
        assertThat(metadata.path("normalized").asString()).isEqualTo("Cao");
        assertThat(metadata.path("evidence")).isEqualTo(json.readTree(expected.toString()));
    }

    @Test
    void invalidEvidenceConsumesAttemptAndNeverWritesResult() {
        long job = seed();
        var claim = claim(UUID.randomUUID());
        var body = MappingRemoteFixtures.result((String) claim.data().get("inputSha256"));
        ((ObjectNode) body.path("personal").path("fullName").path("evidence")).put("start", 3);
        assertThatThrownBy(
                        () -> remote.result("synthetic_worker", id(claim), HASH, body.toString()))
                .isInstanceOf(MappingRemoteException.class)
                .hasMessage("INVALID_EVIDENCE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                String.class,
                                id(claim)))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                                Integer.class,
                                job))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                String.class,
                                job))
                .isEqualTo("PENDING");
    }

    @Test
    void reapersAreSeparatedAndExpiredAttemptCannotCompleteAfterReclaim() {
        long job = seed();
        var claim = claim(UUID.randomUUID());
        expire(id(claim), job);
        assertThat(mapping.reap()).isZero();
        assertThat(remote.reap()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                String.class,
                                id(claim)))
                .isEqualTo("EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT j."NEXT_ATTEMPT_AT">=a."RECLAIM_AFTER" FROM "MAPPING"."JOBS" j
                JOIN "MAPPING"."ATTEMPTS" a ON a."JOB_ID"=j."ID" WHERE j."ID"=?
                """,
                                Boolean.class,
                                job))
                .isTrue();
        assertThat(claim(UUID.randomUUID()).status()).isEqualTo(204);
        jdbc.update(
                "UPDATE \"MAPPING\".\"JOBS\" SET \"NEXT_ATTEMPT_AT\"=clock_timestamp()-interval '1 second' WHERE \"ID\"=?",
                job);
        assertThat(claim(UUID.randomUUID()).status()).isEqualTo(201);
        assertThatThrownBy(() -> remote.result("synthetic_worker", id(claim), HASH, result(claim)))
                .hasMessage("STALE_ATTEMPT");
    }

    @Test
    void workerCannotSupplyServerFailureCodesAndRetryBudgetIsHonored() {
        long job = seed();
        var claim = claim(UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                remote.failure(
                                        "synthetic_worker",
                                        id(claim),
                                        HASH,
                                        "{\"code\":\"LEASE_EXPIRED\"}"))
                .hasMessage("INVALID_RESULT");
        jdbc.update("UPDATE \"MAPPING\".\"JOBS\" SET \"MAX_ATTEMPTS\"=1 WHERE \"ID\"=?", job);
        remote.failure("synthetic_worker", id(claim), HASH, "{\"code\":\"PROVIDER_TIMEOUT\"}");
        remote.failure("synthetic_worker", id(claim), HASH, "{\"code\":\"PROVIDER_TIMEOUT\"}");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                String.class,
                                job))
                .isEqualTo("FAILED");
    }

    @Test
    void migrationRejectsBadHashesDuplicateActiveAttemptsAndInvalidTimes() {
        seed();
        var claim = claim(UUID.randomUUID());
        for (String assignment :
                java.util.List.of(
                        "\"LEASE_TOKEN_HASH\"='bad'",
                        "\"ATTEMPT_NO\"=0",
                        "\"RECLAIM_AFTER\"=\"HARD_DEADLINE_AT\"",
                        "\"PROVENANCE\"='[]'::jsonb")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"MAPPING\".\"ATTEMPTS\" SET "
                                                    + assignment
                                                    + " WHERE \"PUBLIC_ID\"=?",
                                            id(claim)))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                INSERT INTO "MAPPING"."ATTEMPTS" ("PUBLIC_ID","JOB_ID","ATTEMPT_NO","WORKER_ID","CLAIM_REQUEST_ID","CLAIM_BODY_SHA256","LEASE_TOKEN_HASH","LEASE_EXPIRES_AT","HARD_DEADLINE_AT","RECLAIM_AFTER","STATUS","STARTED_AT","PROVENANCE")
                SELECT ?,"JOB_ID",2,"WORKER_ID",?,"CLAIM_BODY_SHA256","LEASE_TOKEN_HASH","LEASE_EXPIRES_AT","HARD_DEADLINE_AT","RECLAIM_AFTER","STATUS","STARTED_AT","PROVENANCE" FROM "MAPPING"."ATTEMPTS" WHERE "PUBLIC_ID"=?
                """,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        id(claim)))
                .isInstanceOf(DataAccessException.class);
    }

    private void expire(UUID id, long job) {
        jdbc.update(
                """
                UPDATE "MAPPING"."ATTEMPTS" SET "STARTED_AT"=clock_timestamp()-interval '3 minutes',
                "LEASE_EXPIRES_AT"=clock_timestamp()-interval '1 second' WHERE "PUBLIC_ID"=?
                """,
                id);
        jdbc.update(
                "UPDATE \"MAPPING\".\"JOBS\" SET \"LEASE_EXPIRES_AT\"=clock_timestamp()-interval '1 second' WHERE \"ID\"=?",
                job);
    }

    @Test
    void heartbeatIsCappedAtDeadlineAndDeadlineExpiryReturns409() {
        long job = seed();
        var claim = claim(UUID.randomUUID());
        jdbc.update(
                """
                UPDATE "MAPPING"."ATTEMPTS" SET "LEASE_EXPIRES_AT"=clock_timestamp()+interval '30 seconds',
                "HARD_DEADLINE_AT"=clock_timestamp()+interval '60 seconds' WHERE "PUBLIC_ID"=?
                """,
                id(claim));
        jdbc.update(
                """
                UPDATE "MAPPING"."JOBS" j SET "LEASE_EXPIRES_AT"=a."LEASE_EXPIRES_AT","HARD_DEADLINE_AT"=a."HARD_DEADLINE_AT"
                FROM "MAPPING"."ATTEMPTS" a WHERE j."ID"=? AND a."PUBLIC_ID"=?
                """,
                job,
                id(claim));
        remote.heartbeat("synthetic_worker", id(claim), HASH, "{}");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"LEASE_EXPIRES_AT\"=\"HARD_DEADLINE_AT\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                Boolean.class,
                                id(claim)))
                .isTrue();
        jdbc.update(
                """
                UPDATE "MAPPING"."ATTEMPTS" SET "STARTED_AT"=clock_timestamp()-interval '3 minutes',
                "LEASE_EXPIRES_AT"=clock_timestamp()-interval '2 seconds',
                "HARD_DEADLINE_AT"=clock_timestamp()-interval '1 second' WHERE "PUBLIC_ID"=?
                """,
                id(claim));
        assertThatThrownBy(() -> remote.heartbeat("synthetic_worker", id(claim), HASH, "{}"))
                .isInstanceOfSatisfying(
                        MappingRemoteException.class,
                        error -> assertThat(error.status()).isEqualTo(409))
                .hasMessage("HARD_DEADLINE_EXCEEDED");
    }

    @Test
    void changedInputFingerprintIsRejectedAndConsumesAttemptWithoutResult() {
        long job = seed();
        var claim = claim(UUID.randomUUID());
        var body = MappingRemoteFixtures.result("b".repeat(64));
        assertThatThrownBy(
                        () -> remote.result("synthetic_worker", id(claim), HASH, body.toString()))
                .hasMessage("INPUT_CHANGED");
        assertThat(mapping.result(job)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                String.class,
                                id(claim)))
                .isEqualTo("FAILED");
    }

    @Test
    void changedInputCannotBlockQueueAndEventuallyExhaustsJobBudget() {
        long broken = seed();
        jdbc.update("UPDATE \"MAPPING\".\"JOBS\" SET \"MAX_ATTEMPTS\"=3 WHERE \"ID\"=?", broken);
        var first = claim(UUID.randomUUID());
        // Simulate a changed extraction fingerprint after the worker pinned its input.
        jdbc.update(
                "UPDATE \"MAPPING\".\"JOBS\" SET \"INPUT_SHA256\"=? WHERE \"ID\"=?",
                "b".repeat(64),
                broken);
        for (int replay = 0; replay < 2; replay++) {
            assertThatThrownBy(
                            () -> remote.result("synthetic_worker", id(first), HASH, result(first)))
                    .isInstanceOfSatisfying(
                            MappingRemoteException.class,
                            error -> assertThat(error.status()).isEqualTo(409))
                    .hasMessage("INPUT_CHANGED");
        }
        long healthy = seed();
        ready(broken);
        assertThat(claim(UUID.randomUUID()).status()).isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ATTEMPTS\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                Integer.class,
                                broken))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"NEXT_ATTEMPT_AT\">clock_timestamp() FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                Boolean.class,
                                broken))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"JOB_ID\"=? AND \"STATUS\"='ACTIVE'",
                                Integer.class,
                                broken))
                .isZero();
        var next = claim(UUID.randomUUID());
        assertThat(next.status()).isEqualTo(201);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"JOB_ID\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                Long.class,
                                id(next)))
                .isEqualTo(healthy);
        ready(broken);
        assertThat(claim(UUID.randomUUID()).status()).isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                String.class,
                                broken))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ATTEMPTS\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                Integer.class,
                                broken))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ERROR_CODE\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                String.class,
                                broken))
                .isEqualTo("INPUT_CHANGED");
        assertThat(claim(UUID.randomUUID()).status()).isEqualTo(204);
    }

    private void ready(long job) {
        jdbc.update(
                "UPDATE \"MAPPING\".\"JOBS\" SET \"NEXT_ATTEMPT_AT\"=clock_timestamp()-interval '1 second' WHERE \"ID\"=?",
                job);
    }

    @Test
    void claimBodyConflictTakesPrecedenceOverWrongLeaseToken() {
        seed();
        UUID request = UUID.randomUUID();
        claim(request);
        String changed =
                MappingRemoteFixtures.claimBody()
                        .toString()
                        .replace("synthetic-model", "synthetic-other");
        assertThatThrownBy(() -> remote.claim("synthetic_worker", request, "b".repeat(64), changed))
                .hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                remote.claim(
                                        "synthetic_worker",
                                        request,
                                        "b".repeat(64),
                                        MappingRemoteFixtures.claimBody().toString()))
                .hasMessage("INVALID_LEASE_TOKEN");
    }

    @Test
    void reaperOnlyClosesExpiredAttemptWhenJobOwnershipHasMoved() {
        for (String mismatch : java.util.List.of("status", "number")) {
            long job = seed();
            var first = claim(UUID.randomUUID());
            expire(id(first), job);
            if (mismatch.equals("status")) {
                jdbc.update(
                        "UPDATE \"MAPPING\".\"JOBS\" SET \"STATUS\"='PENDING',\"TOKEN\"=NULL,\"LEASE_EXPIRES_AT\"=NULL WHERE \"ID\"=?",
                        job);
            } else {
                jdbc.update(
                        "UPDATE \"MAPPING\".\"JOBS\" SET \"ATTEMPTS\"=\"ATTEMPTS\"+1 WHERE \"ID\"=?",
                        job);
            }
            var before = jdbc.queryForMap("SELECT * FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?", job);
            assertThat(remote.reap()).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT * FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?", job))
                    .isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"STATUS\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                    String.class,
                                    id(first)))
                    .isEqualTo("EXPIRED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"ERROR_CODE\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                                    String.class,
                                    id(first)))
                    .isEqualTo("LEASE_EXPIRED");
            // Keep the prior pending job out of the next loop's claim.
            jdbc.update(
                    "UPDATE \"MAPPING\".\"JOBS\" SET \"NEXT_ATTEMPT_AT\"=clock_timestamp()+interval '1 day' WHERE \"ID\"=?",
                    job);
        }
    }
}
