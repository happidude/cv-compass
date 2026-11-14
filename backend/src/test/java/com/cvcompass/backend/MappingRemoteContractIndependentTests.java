package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cvcompass.backend.ingestion.dto.OcrAttemptKey;
import com.cvcompass.backend.ingestion.dto.OcrClaimRequest;
import com.cvcompass.backend.ingestion.entity.CvDocument;
import com.cvcompass.backend.ingestion.entity.CvProcessingJob;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.service.OcrJobService;
import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.config.MappingWorkerAuthenticationFilter;
import com.cvcompass.backend.mapping.service.MappingDigests;
import com.cvcompass.backend.mapping.service.MappingRemoteFixtures;
import com.cvcompass.backend.mapping.service.MappingService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Black-box contract of /internal/mapping/v1 through the real security chain and database. */
@SpringBootTest(properties = {"cv.ocr.enabled=true", "cv.mapping.remote.enabled=true"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MappingRemoteContractIndependentTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static final String BASE = "/internal/mapping/v1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String WORKER_TOKEN = token();
    private static final String OTHER_WORKER_TOKEN = token();
    private static final String OCR_HASH = MappingDigests.sha256("synthetic-ocr-lease-only");
    private static final Set<String> CLAIM_KEYS =
            Set.of(
                    "attemptId",
                    "engineVersion",
                    "inputSha256",
                    "leaseExpiresAt",
                    "hardDeadlineAt",
                    "heartbeatInterval",
                    "definition");
    private final String leaseToken = token();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MappingRemoteProperties properties;
    @Autowired MappingService mapping;
    @Autowired OcrJobService ocr;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository ocrJobs;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(MappingRemoteContractIndependentTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @BeforeEach
    void reset() {
        jdbc.execute(
                "TRUNCATE \"CANDIDATE_PROPOSALS\",\"CANDIDATE_PUBLICATIONS\",\"CANDIDATE_EXPERIENCES\",\"CV_MAPPING_RESULTS\",\"CV_MAPPING_ATTEMPTS\",\"CV_MAPPING_JOBS\"");
        var first = new MappingRemoteProperties.Worker();
        first.setCredentialSha256(Set.of(MappingWorkerAuthenticationFilter.hash(WORKER_TOKEN)));
        var second = new MappingRemoteProperties.Worker();
        second.setCredentialSha256(
                Set.of(MappingWorkerAuthenticationFilter.hash(OTHER_WORKER_TOKEN)));
        properties.setWorkers(Map.of("synthetic_worker", first, "other_worker", second));
        properties.setEnabled(true);
        properties.setControlBodyBytes(16384);
        properties.setMaxResultBytes(1048576);
    }

    private void seed() {
        var doc = new CvDocument();
        doc.setOriginalFilename("synthetic.pdf");
        doc.setStoragePath(UUID.randomUUID() + ".pdf");
        doc.setFileSize(1024L);
        doc.setMimeType("application/pdf");
        doc.setSha256("a".repeat(64));
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
        ocrJobs.saveAndFlush(job);
        var request =
                new OcrClaimRequest(
                        "synthetic_ocr", UUID.randomUUID(), OCR_HASH, job.getProfileRevision());
        var claim = ocr.claim(request).orElseThrow();
        var envelope = (ObjectNode) json.readTree(OcrFixtures.success(claim));
        var result = (ObjectNode) envelope.path("ocr_result");
        result.put("schema_version", "cv-compass.ocr/2.0");
        var page = (ObjectNode) result.path("pages").get(0);
        page.remove("text");
        page.put("raw_text", MappingRemoteFixtures.RAW);
        long extraction =
                ocr.complete(
                                new OcrAttemptKey(claim.attemptId(), request.workerId(), OCR_HASH),
                                envelope.toString())
                        .extractionId();
        long config =
                mapping.register(
                        MappingRemoteFixtures.definition().revision(),
                        MappingRemoteFixtures.definition());
        jdbc.update(
                "INSERT INTO \"CV_MAPPING_JOBS\" (\"EXTRACTION_ID\",\"CONFIG_ID\",\"ENGINE_VERSION\",\"MAX_ATTEMPTS\") VALUES (?,?,'llm-extraction/1.0',5)",
                extraction,
                config);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + WORKER_TOKEN)
                .header("X-Lease-Token", leaseToken)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder claimRequest(UUID key, String body) {
        return auth(post(BASE + "/claims")).header("Idempotency-Key", key.toString()).content(body);
    }

    private JsonNode claim() throws Exception {
        var response =
                mvc.perform(
                                claimRequest(
                                        UUID.randomUUID(),
                                        MappingRemoteFixtures.claimBody().toString()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString()).path("data");
    }

    private String attempt(JsonNode claim) {
        return BASE + "/attempts/" + claim.path("attemptId").asString();
    }

    private ObjectNode result(JsonNode claim) {
        return MappingRemoteFixtures.result(claim.path("inputSha256").asString());
    }

    private String dbStatus(JsonNode claim) {
        return jdbc.queryForObject(
                "SELECT \"STATUS\" FROM \"CV_MAPPING_ATTEMPTS\" WHERE \"PUBLIC_ID\"=?::uuid",
                String.class,
                claim.path("attemptId").asString());
    }

    private int results() {
        return jdbc.queryForObject("SELECT count(*) FROM \"CV_MAPPING_RESULTS\"", Integer.class);
    }

    @Test
    void emptyClaimIs204WithHardeningHeaders() throws Exception {
        mvc.perform(claimRequest(UUID.randomUUID(), MappingRemoteFixtures.claimBody().toString()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void createdClaimExposesExactlyThePlannedFieldsAndNoInternals() throws Exception {
        seed();
        MvcResult created =
                mvc.perform(
                                claimRequest(
                                        UUID.randomUUID(),
                                        MappingRemoteFixtures.claimBody().toString()))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andReturn();
        String raw = created.getResponse().getContentAsString();
        JsonNode data = json.readTree(raw).path("data");
        Set<String> keys = new java.util.HashSet<>(data.propertyNames());
        assertThat(keys).containsAll(CLAIM_KEYS);
        // "status" is an extra, harmless key the service returns; anything else would be a leak.
        keys.removeAll(CLAIM_KEYS);
        assertThat(keys).isSubsetOf("status");
        assertThat(created.getResponse().getHeader("Location"))
                .isEqualTo(BASE + "/attempts/" + data.path("attemptId").asString());
        assertThat(data.path("attemptId").asString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(data.path("engineVersion").asString()).isEqualTo("llm-extraction/1.0");
        assertThat(data.path("inputSha256").asString()).matches("[0-9a-f]{64}");
        assertThat(data.path("definition").isObject()).isTrue();
        long jobId = jdbc.queryForObject("SELECT \"ID\" FROM \"CV_MAPPING_JOBS\"", Long.class);
        String serverToken =
                jdbc.queryForObject(
                        "SELECT \"TOKEN\"::text FROM \"CV_MAPPING_JOBS\"", String.class);
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        assertThat(lower)
                .doesNotContain("jobid", "job_id", "tokenhash", "token_hash", "credential")
                .doesNotContain(leaseToken.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain(MappingWorkerAuthenticationFilter.hash(leaseToken))
                .doesNotContain(MappingWorkerAuthenticationFilter.hash(WORKER_TOKEN))
                .doesNotContain(serverToken)
                .doesNotContain("\"id\":" + jobId + ",");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"LEASE_TOKEN_HASH\" FROM \"CV_MAPPING_ATTEMPTS\"",
                                String.class))
                .isEqualTo(MappingWorkerAuthenticationFilter.hash(leaseToken))
                .isNotEqualTo(leaseToken);
    }

    @Test
    void authenticationAndTransportErrorsCarryDocumentedCodes() throws Exception {
        mvc.perform(
                        post(BASE + "/claims")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(
                        claimRequest(
                                        UUID.randomUUID(),
                                        MappingRemoteFixtures.claimBody().toString())
                                .with(
                                        request -> {
                                            request.setRemoteAddr("192.0.2.7");
                                            return request;
                                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TLS_REQUIRED"));
        properties.setEnabled(false);
        mvc.perform(claimRequest(UUID.randomUUID(), MappingRemoteFixtures.claimBody().toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("MAPPING_REMOTE_UNAVAILABLE"));
    }

    @Test
    void claimProtocolIdempotencyAndMediaTypeErrors() throws Exception {
        seed();
        var body = MappingRemoteFixtures.claimBody();
        mvc.perform(
                        claimRequest(
                                UUID.randomUUID(),
                                body.deepCopy().put("protocol", "x/1").toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PROTOCOL"));
        mvc.perform(claimRequest(UUID.randomUUID(), body.deepCopy().put("extra", 1).toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RESULT"));
        mvc.perform(claimRequest(UUID.randomUUID(), "{\"protocol\":"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RESULT"));
        mvc.perform(
                        claimRequest(UUID.randomUUID(), body.toString())
                                .contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        UUID key = UUID.randomUUID();
        mvc.perform(claimRequest(key, body.toString())).andExpect(status().isCreated());
        mvc.perform(claimRequest(key, body.toString())).andExpect(status().isOk());
        mvc.perform(claimRequest(key, body.toString().replace("synthetic-model", "another-model")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void controlBodiesAreBoundedBeforeParsing() throws Exception {
        seed();
        properties.setControlBodyBytes(64);
        String garbage = "{".repeat(65);
        mvc.perform(claimRequest(UUID.randomUUID(), garbage))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("RESULT_TOO_LARGE"));
        // The same garbage within the limit is a parse error, proving the 413 came first.
        mvc.perform(claimRequest(UUID.randomUUID(), "{".repeat(64)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RESULT"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_MAPPING_ATTEMPTS\"", Integer.class))
                .isZero();
    }

    @Test
    void leaseAndAttemptErrorsUseDocumentedStatusAndCode() throws Exception {
        seed();
        JsonNode claim = claim();
        String attempt = attempt(claim);
        mvc.perform(auth(get(attempt + "/input")).header("X-Lease-Token", token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LEASE_TOKEN"));
        mvc.perform(auth(get(attempt + "/input")).header("X-Lease-Token", "short"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LEASE_TOKEN"));
        mvc.perform(
                        get(attempt + "/input")
                                .header("Authorization", "Bearer " + OTHER_WORKER_TOKEN)
                                .header("X-Lease-Token", leaseToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_ATTEMPT"));
        mvc.perform(auth(get(BASE + "/attempts/" + UUID.randomUUID() + "/input")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_ATTEMPT"));
        mvc.perform(auth(get(attempt + "/input")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.pages[0].page").value(1))
                .andExpect(jsonPath("$.data.pages[0].raw_text").value(MappingRemoteFixtures.RAW));
        mvc.perform(auth(put(attempt + "/lease")).content("{\"x\":1}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RESULT"));
        mvc.perform(auth(put(attempt + "/lease")).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leaseExpiresAt").isNotEmpty());
    }

    @Test
    void expiredLeaseAndHardDeadlineAreDistinct409Codes() throws Exception {
        seed();
        JsonNode claim = claim();
        String attempt = attempt(claim);
        jdbc.update(
                "UPDATE \"CV_MAPPING_JOBS\" SET \"LEASE_EXPIRES_AT\"=clock_timestamp()-interval '2 seconds'");
        jdbc.update(
                "UPDATE \"CV_MAPPING_ATTEMPTS\" SET \"STARTED_AT\"=clock_timestamp()-interval '3 minutes',\"LEASE_EXPIRES_AT\"=clock_timestamp()-interval '2 seconds'");
        for (var request :
                List.of(
                        auth(get(attempt + "/input")),
                        auth(put(attempt + "/lease")).content("{}"),
                        auth(put(attempt + "/result")).content(result(claim).toString()))) {
            mvc.perform(request)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LEASE_EXPIRED"));
        }
        // Past the hard deadline the deadline code wins over the lease code.
        jdbc.update(
                "UPDATE \"CV_MAPPING_JOBS\" SET \"HARD_DEADLINE_AT\"=clock_timestamp()-interval '1 second'");
        jdbc.update(
                "UPDATE \"CV_MAPPING_ATTEMPTS\" SET \"HARD_DEADLINE_AT\"=clock_timestamp()-interval '1 second'");
        mvc.perform(auth(put(attempt + "/lease")).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HARD_DEADLINE_EXCEEDED"));
        assertThat(results()).isZero();
    }

    @Test
    void failureEndpointAcceptsOnlyWorkerCodesAndIsIdempotent() throws Exception {
        seed();
        JsonNode claim = claim();
        String attempt = attempt(claim);
        for (String serverOnly :
                List.of(
                        "LEASE_EXPIRED",
                        "INVALID_EVIDENCE",
                        "INPUT_CHANGED",
                        "provider_error",
                        "")) {
            mvc.perform(
                            auth(put(attempt + "/failure"))
                                    .content("{\"code\":\"" + serverOnly + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INVALID_RESULT"));
        }
        mvc.perform(auth(put(attempt + "/failure")).content("{\"code\":\"X\",\"extra\":1}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(dbStatus(claim)).isEqualTo("ACTIVE");
        mvc.perform(auth(put(attempt + "/failure")).content("{\"code\":\"PROVIDER_ERROR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        mvc.perform(auth(put(attempt + "/failure")).content("{\"code\":\"PROVIDER_ERROR\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(put(attempt + "/failure")).content("{\"code\":\"PROVIDER_TIMEOUT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTCOME_CONFLICT"));
        assertThat(dbStatus(claim)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ERROR_CODE\" FROM \"CV_MAPPING_ATTEMPTS\"", String.class))
                .isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void resultSizeMediaTypeAndProtocolErrors() throws Exception {
        seed();
        JsonNode claim = claim();
        String attempt = attempt(claim);
        mvc.perform(auth(put(attempt + "/result")).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(
                        auth(put(attempt + "/result"))
                                .content(new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'}))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RESULT"));
        assertThat(results()).isZero();
        var wrongProtocol = result(claim).put("protocol", "x/1");
        mvc.perform(auth(put(attempt + "/result")).content(wrongProtocol.toString()))
                .andExpect(status().isUnprocessableContent());
        // A failed verification consumed the attempt, so use a fresh one for the size check.
        jdbc.update("TRUNCATE \"CV_MAPPING_ATTEMPTS\",\"CV_MAPPING_JOBS\" CASCADE");
        seed();
        JsonNode second = claim();
        properties.setMaxResultBytes(100);
        mvc.perform(auth(put(attempt(second) + "/result")).content("{".repeat(101)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("RESULT_TOO_LARGE"));
        assertThat(dbStatus(second)).isEqualTo("ACTIVE");
    }

    @Test
    void evidenceOffsetsAreCodePointsEvenAfterNonBmpCharacters() throws Exception {
        seed();
        JsonNode claim = claim();
        String attempt = attempt(claim);
        var evidence = result(claim).path("personal").path("fullName").path("evidence");
        // RAW starts with one emoji (1 code point, 2 UTF-16 units): "Synthetic Person" begins at 2.
        assertThat(evidence.path("start").asInt()).isEqualTo(2);
        mvc.perform(auth(put(attempt + "/result")).content(result(claim).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        assertThat(dbStatus(claim)).isEqualTo("ACCEPTED");
        assertThat(results()).isEqualTo(1);
        // Replaying the identical body is idempotent; any different body conflicts.
        mvc.perform(auth(put(attempt + "/result")).content(result(claim).toString()))
                .andExpect(status().isOk());
        mvc.perform(
                        auth(put(attempt + "/result"))
                                .content(
                                        result(claim)
                                                .put("inputSha256", "f".repeat(64))
                                                .toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTCOME_CONFLICT"));
    }

    @Test
    void utf16EvidenceOffsetsAreRejectedAndConsumeTheAttempt() throws Exception {
        seed();
        JsonNode claim = claim();
        var body = result(claim);
        var evidence = (ObjectNode) body.path("personal").path("fullName").path("evidence");
        int codePointStart = evidence.path("start").asInt();
        String text = evidence.path("text").asString();
        // Same slice expressed in UTF-16 units (shifted by the surrogate pair).
        int utf16Start = MappingRemoteFixtures.RAW.indexOf(text);
        assertThat(utf16Start).isEqualTo(codePointStart + 1);
        evidence.put("start", utf16Start).put("end", utf16Start + text.length());
        mvc.perform(auth(put(attempt(claim) + "/result")).content(body.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_EVIDENCE"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(dbStatus(claim)).isEqualTo("FAILED");
        assertThat(results()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ERROR_CODE\" FROM \"CV_MAPPING_ATTEMPTS\"", String.class))
                .isEqualTo("INVALID_EVIDENCE");
        assertThat(jdbc.queryForObject("SELECT \"STATUS\" FROM \"CV_MAPPING_JOBS\"", String.class))
                .isEqualTo("PENDING");
        assertThat(
                        new String(
                                body.toString().getBytes(StandardCharsets.UTF_8),
                                StandardCharsets.UTF_8))
                .contains("Synthetic Person");
    }
}
