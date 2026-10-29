package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cvcompass.backend.candidate.dto.Profile;
import com.cvcompass.backend.candidate.exception.CandidateFailure;
import com.cvcompass.backend.candidate.repository.PublicationRepository;
import com.cvcompass.backend.candidate.service.CandidateQueryService;
import com.cvcompass.backend.candidate.service.CandidateReviewService;
import com.cvcompass.backend.candidate.service.CandidateRevisionService;
import com.cvcompass.backend.candidate.service.CandidateService;
import com.cvcompass.backend.candidate.service.PublicationService;
import com.cvcompass.backend.candidate.service.PublicationSweep;
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
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.MappingDefinition;
import com.cvcompass.backend.mapping.dto.MappingDefinition.Field;
import com.cvcompass.backend.mapping.repository.CompletedMappingRepository;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.storage.FileStorageService;
import com.cvcompass.backend.storage.StorageKey;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"cv.ocr.enabled=true", "cv.candidate.publication.enabled=false"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(M11IntegrationTests.WorkerProbe.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class M11IntegrationTests {
    @Test
    void historyFailureRollsBackPublicationThroughItsSpringProxy() throws Exception {
        var doc = document();
        long resultId = result(doc, raw(), false);
        long profilesBefore =
                jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATE_PROFILES\"", Long.class);
        long experiencesBefore =
                jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATE_EXPERIENCES\"", Long.class);
        jdbc.execute(
                "ALTER TABLE \"CANDIDATE_HISTORY\" ADD CONSTRAINT \"TEST_FAIL_PUBLICATION_HISTORY\" CHECK (\"ACTION\"<>'PUBLISH_MAPPING') NOT VALID");
        try {
            assertThatThrownBy(() -> publication.publish(resultId))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                                    Long.class,
                                    doc.getId()))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                    Long.class,
                                    resultId))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CANDIDATE_PROFILES\"", Long.class))
                    .isEqualTo(profilesBefore);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CANDIDATE_EXPERIENCES\"", Long.class))
                    .isEqualTo(experiencesBefore);
        } finally {
            jdbc.execute(
                    "ALTER TABLE \"CANDIDATE_HISTORY\" DROP CONSTRAINT \"TEST_FAIL_PUBLICATION_HISTORY\"");
        }
        assertThat(publication.publish(resultId)).isTrue();
    }

    @Test
    void historyFailureRollsBackEditAndReviewThroughTheirSpringProxies() throws Exception {
        var doc = document();
        var profile = published(doc);
        publication.publish(result(doc, raw(), false));
        profile = queries.detail(profile.id());
        var before = profile;
        var proposal = queries.proposals(profile.id(), null, 100).getFirst();
        var historyBefore = revisions.history(profile.id(), null, 100);
        jdbc.execute(
                "ALTER TABLE \"CANDIDATE_HISTORY\" ADD CONSTRAINT \"TEST_FAIL_USER_HISTORY\" CHECK (\"ACTION\" NOT IN ('EDIT_BASIC','ACCEPT_PROPOSAL')) NOT VALID");
        try {
            assertThatThrownBy(
                            () ->
                                    candidates.basic(
                                            before.id(),
                                            before.version(),
                                            json.readTree("{\"fullName\":\"Synthetic rollback\"}"),
                                            false,
                                            "editor"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(queries.detail(before.id())).isEqualTo(before);
            assertThatThrownBy(
                            () ->
                                    reviews.decide(
                                            before.id(),
                                            before.version(),
                                            proposal.id(),
                                            "ACCEPT",
                                            before.experiences().getFirst().id(),
                                            null,
                                            "reviewer"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(queries.detail(before.id())).isEqualTo(before);
            assertThat(queries.proposals(before.id(), null, 100)).containsExactly(proposal);
            assertThat(revisions.history(before.id(), null, 100)).isEqualTo(historyBefore);
        } finally {
            jdbc.execute(
                    "ALTER TABLE \"CANDIDATE_HISTORY\" DROP CONSTRAINT \"TEST_FAIL_USER_HISTORY\"");
        }
    }

    @Test
    void missingOrInvalidDatePrecisionReturnsValidationError() throws Exception {
        var p = published(document());
        for (String precision : List.of("missing", "INVALID", "oversized")) {
            var data = (ObjectNode) p.experiences().getFirst().data().deepCopy();
            var start = (ObjectNode) data.path("period").path("start");
            if ("oversized".equals(precision)) start.put("year", 4294969296L);
            else if ("missing".equals(precision)) start.remove("precision");
            else start.put("precision", precision);
            assertThatThrownBy(
                            () ->
                                    candidates.editExperience(
                                            p.id(),
                                            p.version(),
                                            p.experiences().getFirst().id(),
                                            data,
                                            "SAVE",
                                            "editor"))
                    .hasMessage("VALIDATION_ERROR");
        }
    }

    @Test
    void editingKnownEmployerDoesNotRequireInventingUnknownTitle() throws Exception {
        var doc = document();
        publication.publish(
                result(doc, "WORK EXPERIENCE\nEmployer: Meadow Labs\nDates: 2018 - 2020", false));
        var p = queries.detail(candidate(doc));
        var data = (ObjectNode) p.experiences().getFirst().data().deepCopy();
        ((ObjectNode) data.path("company")).put("raw", "Synthetic corrected employer");
        var edited =
                candidates.editExperience(
                        p.id(),
                        p.version(),
                        p.experiences().getFirst().id(),
                        data,
                        "SAVE",
                        "editor");
        assertThat(edited.experiences().getFirst().data().path("title").path("raw").isNull())
                .isTrue();
        assertThat(edited.experiences().getFirst().data().path("title").path("state").asString())
                .isEqualTo("NOT_FOUND");
        assertThat(edited.experiences().getFirst().data().path("company").path("raw").asString())
                .isEqualTo("Synthetic corrected employer");
    }

    @Test
    void userCorrectionReplacesOldMappingStatesWithUserProposals() throws Exception {
        var profile = published(document());
        var data = (ObjectNode) profile.experiences().getFirst().data().deepCopy();
        ((ObjectNode) data.path("title"))
                .put("state", "AMBIGUOUS")
                .put("functionState", "NOT_FOUND")
                .putArray("functionCodes")
                .add("USER_FUNCTION");
        ((ObjectNode) data.path("company"))
                .put("industryState", "NOT_FOUND")
                .putArray("industryCodes")
                .add("USER_INDUSTRY");
        var edited =
                candidates.editExperience(
                        profile.id(),
                        profile.version(),
                        profile.experiences().getFirst().id(),
                        data,
                        "SAVE",
                        "corrector");
        var value = edited.experiences().getFirst().data();
        assertThat(value.path("title").path("state").asString()).isEqualTo("PROPOSED");
        assertThat(value.path("title").path("functionState").asString()).isEqualTo("PROPOSED");
        assertThat(value.path("company").path("industryState").asString()).isEqualTo("PROPOSED");
        assertThat(edited.experiences().getFirst().origin()).isEqualTo("USER");
    }

    @Test
    void malformedEditCommandsFailValidationAndHttpVersionConflictsStay409() throws Exception {
        var profile = published(document());
        var login = login();
        for (String body :
                List.of("{\"version\":" + profile.version() + "}", "{\"operation\":\"DELETE\"}")) {
            mvc.perform(
                            post("/api/candidates/" + profile.id() + "/experiences")
                                    .session(login.session())
                                    .cookie(login.cookie())
                                    .header("X-XSRF-TOKEN", login.token())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        put("/api/candidates/" + profile.id() + "/basic")
                                .session(login.session())
                                .cookie(login.cookie())
                                .header("X-XSRF-TOKEN", login.token())
                                .contentType("application/json")
                                .content("{\"version\":0,\"confirm\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(queries.detail(profile.id()).version()).isEqualTo(profile.version());
    }

    @org.springframework.web.bind.annotation.RestController
    static class WorkerProbe {
        @org.springframework.web.bind.annotation.GetMapping("/internal/ocr/v1/m11-probe")
        String probe(jakarta.servlet.http.HttpServletRequest request) {
            return ((com.cvcompass.backend.ingestion.dto.OcrWorkerPrincipal)
                            request.getAttribute(
                                    com.cvcompass.backend.ingestion.dto.OcrWorkerPrincipal
                                            .ATTRIBUTE))
                    .workerId();
        }
    }

    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    static final List<StorageKey> KEYS = new ArrayList<>();
    private static final byte[] PDF = M11SyntheticPdf.create();
    private static final String HASH = "a".repeat(64);
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PublicationService publication;
    @Autowired CandidateService candidates;
    @Autowired CandidateQueryService queries;
    @Autowired CandidateRevisionService revisions;
    @Autowired CandidateReviewService reviews;
    @Autowired MappingService mapping;
    @Autowired OcrJobService ocr;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired FileStorageService storage;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        var context = new TestContextManager(M11IntegrationTests.class).getTestContext();
        var storage = context.getApplicationContext().getBean(FileStorageService.class);
        for (var key : KEYS) storage.delete(key);
        context.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    CvDocument document() {
        var file = storage.store(new ByteArrayInputStream(PDF));
        KEYS.add(file.key());
        var d = new CvDocument();
        d.setOriginalFilename("synthetic M11\r\nname.pdf");
        d.setStoragePath(file.key().value());
        d.setFileSize(file.sizeBytes());
        d.setMimeType("application/pdf");
        d.setSha256(file.sha256());
        d.setStatus(DocumentStatus.PENDING);
        return documents.saveAndFlush(d);
    }

    long result(CvDocument doc, String raw, boolean v1) throws Exception {
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
        var envelope = (ObjectNode) json.readTree(OcrFixtures.success(claim));
        var data = (ObjectNode) envelope.path("ocr_result");
        data.put("schema_version", "cv-compass.ocr/2.0");
        var page = (ObjectNode) data.path("pages").get(0);
        page.remove("text");
        page.put("raw_text", raw);
        if (raw == null) {
            page.put("status", "failed").put("method", "none").put("raw_text", "");
            page.putObject("error").put("code", "OCR_FAILED").put("stage", "ocr");
            data.put("status", "failed");
            envelope.put("exit_code", 2);
        }
        long extraction =
                ocr.complete(
                                new OcrAttemptKey(claim.attemptId(), request.workerId(), HASH),
                                envelope.toString())
                        .extractionId();
        long config;
        if (v1)
            config =
                    mapping.register(
                            "legacy-" + UUID.randomUUID(),
                            new MappingDefinition(
                                    1,
                                    List.of(
                                            new Field(
                                                    "sample",
                                                    "STRING",
                                                    "PAGE",
                                                    "FIRST_LINE",
                                                    null))));
        else
            try (var stream =
                    getClass().getResourceAsStream("/mapping/experience-config-v2.json")) {
                config =
                        mapping.register(
                                "experience-" + UUID.randomUUID(),
                                json.readValue(stream, ExperienceDefinition.class));
            }
        jdbc.update(
                "INSERT INTO \"CV_MAPPING_JOBS\" (\"EXTRACTION_ID\",\"CONFIG_ID\",\"ENGINE_VERSION\") VALUES (?,?,?)",
                extraction,
                config,
                v1 ? "literal-mapping/1.0" : "experience-mapping/2.0");
        var mappingClaim = mapping.claim().orElseThrow();
        mapping.complete(mappingClaim, mapping.process(mappingClaim));
        return jdbc.queryForObject(
                "SELECT \"ID\" FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                Long.class,
                mappingClaim.jobId());
    }

    String raw() {
        return "WORK EXPERIENCE\nEmployer: Northstar Devices\nTitle: Engineering Manager\nDates: 2018 - 2021\nPartner: Synthetic Client";
    }

    private long candidate(CvDocument d) {
        return jdbc.queryForObject(
                "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                Long.class,
                d.getId());
    }

    private Profile published(CvDocument d) throws Exception {
        publication.publish(result(d, raw(), false));
        return queries.detail(candidate(d));
    }

    @Test
    void actualOcrMappingPublicationIsIdempotentAndPreservesRawSource() throws Exception {
        var doc = document();
        long r = result(doc, raw(), false);
        var ocrBefore =
                jdbc.queryForList(
                        "SELECT * FROM \"CV_EXTRACTIONS\" WHERE \"CV_DOCUMENT_ID\"=?", doc.getId());
        assertThat(publication.publish(r)).isTrue();
        assertThat(publication.publish(r)).isFalse();
        var p = queries.detail(candidate(doc));
        assertThat(p.basic()).isEmpty();
        assertThat(p.experiences()).hasSize(1);
        assertThat(p.experiences().getFirst().state()).isEqualTo("UNVERIFIED");
        assertThat(p.experiences().getFirst().data().path("evidence")).isNotEmpty();
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM \"CV_EXTRACTIONS\" WHERE \"CV_DOCUMENT_ID\"=?",
                                doc.getId()))
                .isEqualTo(ocrBefore);
    }

    @Test
    void concurrentPublicationResolvesOneIdentityAndOneLedger() throws Exception {
        var doc = document();
        long r = result(doc, raw(), false);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                barrier.await();
                                return publication.publish(r);
                            });
            var b =
                    executor.submit(
                            () -> {
                                barrier.await();
                                return publication.publish(r);
                            });
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(queries.detail(candidate(doc)).experiences()).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                Integer.class,
                                r))
                .isEqualTo(1);
    }

    @Test
    void rollbackRestartsWithoutPartialIdentityHistoryOrLedger() throws Exception {
        var doc = document();
        long r = result(doc, raw(), false);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            publication.publish(r);
                            status.setRollbackOnly();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                                Long.class,
                                doc.getId()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                Integer.class,
                                r))
                .isZero();
        assertThat(publication.publish(r)).isTrue();
        assertThat(queries.detail(candidate(doc)).version()).isEqualTo(1);
    }

    @Test
    void lateOlderResultIsSupersededAndV1OrEmptyNeverCreatesCandidate() throws Exception {
        var doc = document();
        long older = result(doc, raw(), false),
                newer = result(doc, raw().replace("2018", "2019"), false);
        publication.publish(newer);
        var before = queries.detail(candidate(doc));
        publication.publish(older);
        assertThat(queries.detail(candidate(doc))).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                older))
                .isEqualTo("SUPERSEDED");
        for (boolean legacy : List.of(true, false)) {
            var other = document();
            long skipped = result(other, "No employment information", legacy);
            publication.publish(skipped);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                                    Long.class,
                                    other.getId()))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"STATUS\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                    String.class,
                                    skipped))
                    .isEqualTo("SKIPPED");
        }
    }

    @Test
    void userValuesConfirmationAndTombstoneSurviveRemappingWithProposals() throws Exception {
        var doc = document();
        var p = published(doc);
        UUID key = p.experiences().getFirst().id();
        var data = (ObjectNode) p.experiences().getFirst().data().deepCopy();
        ((ObjectNode) data.path("title")).put("raw", "Synthetic USER title");
        var edited = candidates.editExperience(p.id(), p.version(), key, data, "SAVE", "editor");
        assertThat(edited.experiences().getFirst().origin()).isEqualTo("USER");
        assertThat(edited.experiences().getFirst().data().path("evidence").isMissingNode())
                .isTrue();
        var basic =
                candidates.basic(
                        p.id(),
                        edited.version(),
                        json.readTree("{\"fullName\":\"Synthetic user\"}"),
                        false,
                        "editor");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"FULL_NAME\" FROM \"CANDIDATE_PROFILES\" WHERE \"CANDIDATE_ID\"=?",
                                String.class,
                                p.id()))
                .isEqualTo("Synthetic user");
        assertThat(queries.list(null, 100).content())
                .anySatisfy(
                        row -> {
                            assertThat(row.id()).isEqualTo(p.id());
                            assertThat(row.fullName()).isEqualTo("Synthetic user");
                        });
        var confirmed =
                candidates.editExperience(
                        p.id(), basic.version(), key, null, "CONFIRM", "reviewer");
        long r = result(doc, raw(), false);
        publication.publish(r);
        var remapped = queries.detail(p.id());
        assertThat(remapped.experiences()).isEqualTo(confirmed.experiences());
        assertThat(remapped.basic()).isEqualTo(basic.basic());
        assertThat(queries.proposals(p.id(), null, 100)).hasSize(1);
        var deleted =
                candidates.editExperience(
                        p.id(), remapped.version(), key, null, "DELETE", "editor");
        publication.publish(result(doc, raw(), false));
        assertThat(queries.detail(p.id()).experiences()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"DELETED\" FROM \"CANDIDATE_EXPERIENCES\" WHERE \"ID\"=?",
                                Boolean.class,
                                key))
                .isTrue();
        assertThat(revisions.history(p.id(), null, 100))
                .anySatisfy(
                        h -> {
                            assertThat(h.actor()).isEqualTo("editor");
                            assertThat(h.action()).isEqualTo("SAVE_EXPERIENCE");
                            assertThat(h.before().toString()).contains("evidence");
                        });
    }

    @Test
    void optimisticVersionAndReviewRaceDoNotOverwrite() throws Exception {
        var doc = document();
        var p = published(doc);
        candidates.basic(
                p.id(), p.version(), json.readTree("{\"location\":\"Synthetic\"}"), false, "one");
        assertThatThrownBy(
                        () ->
                                candidates.basic(
                                        p.id(), p.version(), json.readTree("{}"), false, "two"))
                .hasMessage("VERSION_CONFLICT");
        publication.publish(result(doc, raw(), false));
        var current = queries.detail(p.id());
        var proposal = queries.proposals(p.id(), null, 100).getFirst();
        var decided =
                reviews.decide(
                        p.id(),
                        current.version(),
                        proposal.id(),
                        "ACCEPT",
                        current.experiences().getFirst().id(),
                        null,
                        "reviewer");
        assertThat(decided.experiences()).hasSize(1);
        assertThat(decided.experiences().getFirst().state()).isEqualTo("CONFIRMED");
        assertThatThrownBy(
                        () ->
                                reviews.decide(
                                        p.id(),
                                        current.version(),
                                        proposal.id(),
                                        "REJECT",
                                        null,
                                        null,
                                        "other"))
                .hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(
                        () ->
                                reviews.decide(
                                        p.id(),
                                        decided.version(),
                                        proposal.id(),
                                        "REJECT",
                                        null,
                                        null,
                                        "other"))
                .hasMessage("PROPOSAL_CLOSED");
    }

    @Test
    void selectedCandidateCanLinkAnotherDocumentButAssociationCannotBeOverwritten()
            throws Exception {
        var first = document();
        var p = published(first);
        var extra = document();
        var linked = candidates.attach(p.id(), p.version(), extra.getId(), "operator");
        publication.publish(result(extra, raw(), false));
        assertThat(candidate(extra)).isEqualTo(p.id());
        assertThat(queries.detail(p.id()).documents()).hasSize(2);
        assertThat(queries.detail(p.id()).experiences()).hasSize(1);
        assertThat(queries.proposals(p.id(), null, 100)).hasSize(1);
        var second = published(document());
        assertThatThrownBy(
                        () ->
                                candidates.attach(
                                        second.id(), second.version(), extra.getId(), "operator"))
                .hasMessage("DOCUMENT_ALREADY_LINKED");
    }

    private record Login(
            MockHttpSession session, String token, jakarta.servlet.http.Cookie cookie) {}

    private Login login() throws Exception {
        jdbc.update(
                "INSERT INTO \"CV_USERS\"(\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-user',?,'USER') ON CONFLICT DO NOTHING",
                new BCryptPasswordEncoder(4).encode("synthetic-test-only"));
        var csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token =
                json.readTree(csrf.getResponse().getContentAsString()).path("token").asString();
        var cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        var response =
                mvc.perform(
                                post("/api/auth/login")
                                        .cookie(cookie)
                                        .header("X-XSRF-TOKEN", token)
                                        .param("username", "synthetic-user")
                                        .param("password", "synthetic-test-only"))
                        .andExpect(status().isNoContent())
                        .andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        var refreshed = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        return new Login(
                session,
                json.readTree(refreshed.getResponse().getContentAsString())
                        .path("token")
                        .asString(),
                refreshed.getResponse().getCookie("XSRF-TOKEN"));
    }

    @Test
    void sessionsProtectOldAndNewApisAndCsrfLogoutIsEnforced() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/candidates")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").param("username", "bad").param("password", "bad"))
                .andExpect(status().isForbidden());
        var login = login();
        mvc.perform(get("/api/auth/me").session(login.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("synthetic-user"));
        mvc.perform(
                        put("/api/candidates/1/basic")
                                .session(login.session())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/auth/logout")
                                .session(login.session())
                                .cookie(login.cookie())
                                .header("X-XSRF-TOKEN", login.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/documents").header("Authorization", "Bearer " + "s".repeat(43)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pdfStreamsRangesSafeFilenameAndMissingStorageWithoutMetadataLeak() throws Exception {
        var doc = document();
        var login = login();
        mvc.perform(get("/api/documents/" + doc.getId() + "/content"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/documents/" + doc.getId() + "/content").session(login.session()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        "inline; filename=\"document-" + doc.getId() + ".pdf\""));
        mvc.perform(
                        get("/api/documents/" + doc.getId() + "/content")
                                .session(login.session())
                                .header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes("%PDF".getBytes()))
                .andExpect(header().string("Content-Range", "bytes 0-3/" + PDF.length));
        mvc.perform(
                        get("/api/documents/" + doc.getId() + "/content?download=true")
                                .session(login.session())
                                .header("Range", "bytes=-5"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes("%%EOF".getBytes()));
        for (String range : List.of("bytes=999-", "bytes=4-2", "bytes=0-1,4-5", "bytes=-0", "bad"))
            mvc.perform(
                            get("/api/documents/" + doc.getId() + "/content")
                                    .session(login.session())
                                    .header("Range", range))
                    .andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string("Content-Range", "bytes */" + PDF.length));
        storage.delete(new StorageKey(doc.getStoragePath()));
        mvc.perform(get("/api/documents/" + doc.getId() + "/content").session(login.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORAGE_FILE_MISSING"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        doc.getStoragePath()))));
        mvc.perform(get("/api/documents/9223372036854775807/content").session(login.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void publicationAndUserDateValidationRejectInvalidValues() throws Exception {
        var p = published(document());
        var data = (ObjectNode) p.experiences().getFirst().data().deepCopy();
        ((ObjectNode) data.path("period").path("start")).put("year", 0);
        assertThatThrownBy(
                        () ->
                                candidates.editExperience(
                                        p.id(),
                                        p.version(),
                                        p.experiences().getFirst().id(),
                                        data,
                                        "SAVE",
                                        "operator"))
                .hasMessage("VALIDATION_ERROR");
        assertThat(queries.detail(p.id()).version()).isEqualTo(p.version());
    }

    @Test
    void unsupportedV2IsRecordedWithoutCreatingProfile() throws Exception {
        var doc = document();
        long id = result(doc, null, false);
        assertThat(publication.publish(id)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                id))
                .isEqualTo("SKIPPED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                                Long.class,
                                doc.getId()))
                .isNull();
    }

    @Test
    void boundedSweepRestartsAndReconcilesWithoutNewArrivalsExtendingUpperBound() throws Exception {
        result(document(), raw(), false);
        result(document(), raw(), false);
        jdbc.update(
                "UPDATE \"CANDIDATE_PUBLICATION_SETTINGS\" SET \"AFTER_ID\"=0,\"UPPER_ID\"=NULL WHERE \"ID\"=1");
        var sweep =
                new PublicationSweep(
                        new PublicationRepository(jdbc),
                        new CompletedMappings(new CompletedMappingRepository(jdbc)));
        var tx = new TransactionTemplate(transactions);
        var observed = new ArrayList<Long>(tx.execute(status -> sweep.reserve(1)));
        Long upper =
                jdbc.queryForObject(
                        "SELECT \"UPPER_ID\" FROM \"CANDIDATE_PUBLICATION_SETTINGS\" WHERE \"ID\"=1",
                        Long.class);
        assertThat(upper).isNotNull();
        long later = result(document(), raw(), false);
        assertThat(later).isGreaterThan(upper);
        var restarted =
                new PublicationSweep(
                        new PublicationRepository(jdbc),
                        new CompletedMappings(new CompletedMappingRepository(jdbc)));
        while (jdbc.queryForObject(
                        "SELECT \"UPPER_ID\" FROM \"CANDIDATE_PUBLICATION_SETTINGS\" WHERE \"ID\"=1",
                        Long.class)
                != null) observed.addAll(tx.execute(status -> restarted.reserve(1)));
        assertThat(observed).doesNotContain(later);
        var reconciled = new ArrayList<Long>();
        do {
            reconciled.addAll(tx.execute(status -> restarted.reserve(1)));
        } while (jdbc.queryForObject(
                        "SELECT \"UPPER_ID\" FROM \"CANDIDATE_PUBLICATION_SETTINGS\" WHERE \"ID\"=1",
                        Long.class)
                != null);
        assertThat(reconciled).contains(later).containsAll(observed);
    }

    @Test
    void realReviewDecisionRaceCommitsOnlyOneRevision() throws Exception {
        var doc = document();
        var profile = published(doc);
        publication.publish(result(doc, raw(), false));
        var current = queries.detail(profile.id());
        var proposal = queries.proposals(profile.id(), null, 100).getFirst();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                barrier.await();
                                try {
                                    reviews.decide(
                                            profile.id(),
                                            current.version(),
                                            proposal.id(),
                                            "ACCEPT",
                                            current.experiences().getFirst().id(),
                                            null,
                                            "first");
                                    return "OK";
                                } catch (CandidateFailure e) {
                                    return e.getMessage();
                                }
                            });
            var second =
                    executor.submit(
                            () -> {
                                barrier.await();
                                try {
                                    reviews.decide(
                                            profile.id(),
                                            current.version(),
                                            proposal.id(),
                                            "REJECT",
                                            null,
                                            null,
                                            "second");
                                    return "OK";
                                } catch (CandidateFailure e) {
                                    return e.getMessage();
                                }
                            });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        }
        assertThat(queries.detail(profile.id()).version()).isEqualTo(current.version() + 1);
        assertThat(queries.detail(profile.id()).experiences()).hasSize(1);
        var history = revisions.history(profile.id(), null, 1).getFirst();
        assertThat(history.after().path("profile").path("version").asLong())
                .isEqualTo(current.version() + 1);
    }

    @Test
    void associationRaceCannotOverwriteDocumentOwner() throws Exception {
        var first = published(document());
        var second = published(document());
        var extra = document();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                barrier.await();
                                try {
                                    candidates.attach(
                                            first.id(), first.version(), extra.getId(), "one");
                                    return "OK";
                                } catch (CandidateFailure e) {
                                    return e.getMessage();
                                }
                            });
            var c =
                    executor.submit(
                            () -> {
                                barrier.await();
                                try {
                                    candidates.attach(
                                            second.id(), second.version(), extra.getId(), "two");
                                    return "OK";
                                } catch (CandidateFailure e) {
                                    return e.getMessage();
                                }
                            });
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), c.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "DOCUMENT_ALREADY_LINKED");
        }
        assertThat(candidate(extra)).isIn(first.id(), second.id());
    }

    @Test
    void workerAuthenticationRemainsSeparateFromSessionUsers() throws Exception {
        var properties =
                new org.springframework.test.context.TestContextManager(M11IntegrationTests.class)
                        .getTestContext()
                        .getApplicationContext()
                        .getBean(
                                com.cvcompass.backend.ingestion.config.OcrIntegrationProperties
                                        .class);
        var login = login();
        properties.setEnabled(true);
        String credential = "w".repeat(43);
        var worker = new com.cvcompass.backend.ingestion.config.OcrIntegrationProperties.Worker();
        worker.setProfiles(Set.of("synthetic-v1"));
        worker.setCredentialSha256(
                Set.of(
                        com.cvcompass.backend.ingestion.config.OcrWorkerAuthenticationFilter.hash(
                                credential)));
        properties.getWorkers().put("synthetic", worker);
        try {
            mvc.perform(
                            get("/internal/ocr/v1/m11-probe")
                                    .session(login.session())
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(
                            get("/internal/ocr/v1/m11-probe")
                                    .header("Authorization", "Bearer " + credential)
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("synthetic"));
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + credential))
                    .andExpect(status().isUnauthorized());
            var csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
            mvc.perform(
                            post("/api/auth/login")
                                    .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                                    .header(
                                            "X-XSRF-TOKEN",
                                            json.readTree(csrf.getResponse().getContentAsString())
                                                    .path("token")
                                                    .asString())
                                    .param("username", "not-a-user")
                                    .param("password", credential))
                    .andExpect(status().isUnauthorized());
        } finally {
            properties.setEnabled(false);
            properties.getWorkers().remove("synthetic");
        }
    }
}
