package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cvcompass.backend.candidate.exception.CandidateFailure;
import com.cvcompass.backend.candidate.service.CandidateQueryService;
import com.cvcompass.backend.candidate.service.CandidateReviewService;
import com.cvcompass.backend.candidate.service.CandidateRevisionService;
import com.cvcompass.backend.candidate.service.CandidateService;
import com.cvcompass.backend.candidate.service.PublicationService;
import com.cvcompass.backend.ingestion.dto.OcrAttemptKey;
import com.cvcompass.backend.ingestion.dto.OcrClaimRequest;
import com.cvcompass.backend.ingestion.entity.CvDocument;
import com.cvcompass.backend.ingestion.entity.CvProcessingJob;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.service.OcrJobService;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"cv.ocr.enabled=true", "cv.candidate.publication.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalInfoPublicationIntegrationTests {
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
        var context =
                new TestContextManager(PersonalInfoPublicationIntegrationTests.class)
                        .getTestContext();
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
        return result(doc, raw, v1, "personal-info-mapping/3.0");
    }

    long result(CvDocument doc, String raw, boolean v1, String engine) throws Exception {
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
        var tree =
                (ObjectNode)
                        json.readTree(
                                getClass()
                                        .getResourceAsStream(
                                                Set.of(
                                                                        "personal-info-mapping/3.3",
                                                                        "personal-info-mapping/3.4",
                                                                        "personal-info-mapping/3.5")
                                                                .contains(engine)
                                                        ? "/mapping/personal-info-config-v"
                                                                + engine.substring(
                                                                        engine.lastIndexOf('/') + 1)
                                                                + ".json"
                                                        : "/mapping/personal-info-config-v3.json")
                                        .readAllBytes());
        tree.put("revision", "personal-" + UUID.randomUUID());
        tree.put("engineCompatibility", engine);
        var parsed =
                json.treeToValue(
                        tree, com.cvcompass.backend.mapping.dto.PersonalInfoDefinition.class);
        long config = mapping.register(parsed.revision(), parsed);
        jdbc.update(
                "INSERT INTO \"CV_MAPPING_JOBS\" (\"EXTRACTION_ID\",\"CONFIG_ID\",\"ENGINE_VERSION\") VALUES (?,?,?)",
                extraction,
                config,
                engine);
        var mappingClaim = mapping.claim().orElseThrow();
        mapping.complete(mappingClaim, mapping.process(mappingClaim));
        return jdbc.queryForObject(
                "SELECT \"ID\" FROM \"CV_MAPPING_RESULTS\" WHERE \"JOB_ID\"=?",
                Long.class,
                mappingClaim.jobId());
    }

    Long candidate(CvDocument d) {
        return jdbc.queryForObject(
                "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                Long.class,
                d.getId());
    }

    @Test
    void personalOnlyPublishesAndReplayIsNoOp() throws Exception {
        var d = document();
        long r =
                result(
                        d,
                        "Full name: Synthetic Person\nGender: Nam\nEmail: person@example.test",
                        false);
        assertThat(publication.publish(r)).isTrue();
        var p = queries.detail(candidate(d));
        assertThat(p.basic().path("fullName").asString()).isEqualTo("Synthetic Person");
        assertThat(p.basic().path("gender").asString()).isEqualTo("MALE");
        assertThat(p.basicState()).isEqualTo("UNVERIFIED");
        assertThat(p.basicOrigin()).isEqualTo("MAPPING");
        assertThat(
                        p.extensions()
                                .path("personalInfo")
                                .path("fields")
                                .path("gender")
                                .path("evidence")
                                .isEmpty())
                .isFalse();
        assertThat(publication.publish(r)).isFalse();
        assertThat(queries.detail(p.id())).isEqualTo(p);
    }

    @Test
    void optInGrammarPublicationPreservesProvenanceAndReplay() throws Exception {
        var doc = document();
        long id =
                result(
                        doc,
                        "Full name: Grammar Example\nGender: F",
                        false,
                        "personal-info-mapping/3.1");
        assertThat(publication.publish(id)).isTrue();
        var profile = queries.detail(candidate(doc));
        assertThat(profile.basic().path("fullName").asString()).isEqualTo("Grammar Example");
        assertThat(profile.basic().path("gender").asString()).isEqualTo("FEMALE");
        assertThat(profile.extensions().path("mapping").path("engineVersion").asString())
                .isEqualTo("personal-info-mapping/3.1");
        assertThat(
                        profile.extensions()
                                .path("personalInfo")
                                .path("fields")
                                .path("gender")
                                .path("resultId")
                                .asLong())
                .isEqualTo(id);
        assertThat(publication.publish(id)).isFalse();
        assertThat(queries.detail(profile.id())).isEqualTo(profile);
    }

    @Test
    void v32PublicationKeepsEngineProvenanceOnReplay() throws Exception {
        var doc = document();
        long id = result(doc, "Phone: +84 (0)900 000 002", false, "personal-info-mapping/3.2");
        assertThat(publication.publish(id)).isTrue();
        var profile = queries.detail(candidate(doc));
        assertThat(profile.basic().path("phone").asString()).isEqualTo("+84 (0)900 000 002");
        assertThat(profile.extensions().path("mapping").path("engineVersion").asString())
                .isEqualTo("personal-info-mapping/3.2");
        assertThat(publication.publish(id)).isFalse();
        assertThat(queries.detail(profile.id())).isEqualTo(profile);
    }

    @Test
    void v34PublicationPublishesProposesAndKeepsHrEdits() throws Exception {
        assertGrammarPublication("personal-info-mapping/3.4");
    }

    @Test
    void v35PublicationPublishesProposesAndKeepsHrEdits() throws Exception {
        assertGrammarPublication("personal-info-mapping/3.5");
    }

    private void assertGrammarPublication(String engine) throws Exception {
        var doc = document();
        String raw =
                "Full name: Synthetic Person\nGender: Nam\n\nWork Experience\n"
                        + "2020 – 2025 Sample Tea House";
        long id = result(doc, raw, false, engine);
        assertThat(publication.publish(id)).isTrue();
        var profile = queries.detail(candidate(doc));
        assertThat(profile.basic().path("fullName").asString()).isEqualTo("Synthetic Person");
        assertThat(profile.extensions().path("mapping").path("engineVersion").asString())
                .isEqualTo(engine);
        assertThat(profile.experiences())
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.data().path("company").path("raw").asString())
                                        .isEqualTo("Sample Tea House"));
        var row = profile.experiences().getFirst();
        // Year-only 2020 – 2025 spans 2020-01-01..2025-12-31 under ExperienceTotals: 6 years.
        assertThat(profile.experienceSummary().totalMonths()).isEqualTo(72);

        // Republishing identical content is a no-op; changed content never overwrites HR edits.
        assertThat(publication.publish(id)).isFalse();
        var data = (ObjectNode) row.data().deepCopy();
        ((ObjectNode) data.path("company")).put("raw", "Synthetic HR Employer");
        var edited =
                candidates.editExperience(
                        profile.id(), profile.version(), row.id(), data, "SAVE", "editor");
        edited =
                candidates.basic(
                        edited.id(),
                        edited.version(),
                        json.readTree("{\"fullName\":\"HR Edited Person\"}"),
                        false,
                        "editor");
        assertThat(edited.basicState()).isEqualTo("EDITED");
        assertThat(edited.experiences().getFirst().state()).isEqualTo("EDITED");
        long second =
                result(
                        doc,
                        raw.replace("Sample Tea House", "Other Tea House")
                                .replace("Synthetic Person", "Other Person"),
                        false,
                        engine);
        assertThat(publication.publish(second)).isTrue();
        var after = queries.detail(edited.id());
        assertThat(after.basic().path("fullName").asString()).isEqualTo("HR Edited Person");
        assertThat(after.basicState()).isEqualTo("EDITED");
        assertThat(after.experiences().getFirst().origin()).isEqualTo("USER");
        assertThat(after.experiences().getFirst().state()).isEqualTo("EDITED");
        assertThat(after.experiences().getFirst().data().path("company").path("raw").asString())
                .isEqualTo("Synthetic HR Employer");
        var proposals = queries.proposals(after.id(), null, 100);
        assertThat(proposals)
                .anySatisfy(
                        p -> {
                            assertThat(p.kind()).isEqualTo("PERSONAL_INFO");
                            assertThat(p.data().toString()).contains("Other Person");
                        })
                .anySatisfy(
                        p -> {
                            assertThat(p.kind()).isEqualTo("EXPERIENCE");
                            assertThat(p.data().path("company").path("raw").asString())
                                    .isEqualTo("Other Tea House");
                        });

        // CONFIRMED data is protected the same way.
        var confirmed =
                candidates.basic(
                        after.id(), after.version(), json.createObjectNode(), true, "editor");
        confirmed =
                candidates.editExperience(
                        confirmed.id(),
                        confirmed.version(),
                        confirmed.experiences().getFirst().id(),
                        null,
                        "CONFIRM",
                        "editor");
        assertThat(confirmed.basicState()).isEqualTo("CONFIRMED");
        assertThat(confirmed.experiences().getFirst().state()).isEqualTo("CONFIRMED");
        long third =
                result(
                        doc,
                        raw.replace("Sample Tea House", "Third Tea House")
                                .replace("Synthetic Person", "Third Person"),
                        false,
                        engine);
        assertThat(publication.publish(third)).isTrue();
        var last = queries.detail(confirmed.id());
        assertThat(last.basic().path("fullName").asString()).isEqualTo("HR Edited Person");
        assertThat(last.basicState()).isEqualTo("CONFIRMED");
        assertThat(last.experiences().getFirst().state()).isEqualTo("CONFIRMED");
        assertThat(last.experiences().getFirst().data().path("company").path("raw").asString())
                .isEqualTo("Synthetic HR Employer");
    }

    @Test
    void v33PublicationStoresProvinceAndIsoBirthDateWithSourceInMetadata() throws Exception {
        var doc = document();
        long id =
                result(
                        doc,
                        "Full name: Synthetic Person\nDOB: 21/10/1990\n"
                                + "Address: 12 Sample Street, Ward 1, District 7, HCMC",
                        false,
                        "personal-info-mapping/3.3");
        assertThat(publication.publish(id)).isTrue();
        var profile = queries.detail(candidate(doc));
        assertThat(profile.basic().path("location").asString()).isEqualTo("Hồ Chí Minh");
        assertThat(profile.basic().path("dateOfBirth").asString()).isEqualTo("1990-10-21");
        var fields = profile.extensions().path("personalInfo").path("fields");
        assertThat(fields.path("location").path("raw").asString())
                .isEqualTo("12 Sample Street, Ward 1, District 7, HCMC");
        assertThat(fields.path("dateOfBirth").path("raw").asString()).isEqualTo("21/10/1990");
        assertThat(publication.publish(id)).isFalse();
    }

    @Test
    void manualPatchPreservesOmittedFieldsAndRemovesOnlyChangedEvidence() throws Exception {
        var d = document();
        publication.publish(result(d, "Full name: Synthetic Person\nGender: Male", false));
        var p = queries.detail(candidate(d));
        assertThat(
                        candidates.basic(
                                p.id(),
                                p.version(),
                                json.readTree("{\"fullName\":\"  Synthetic  Person  \"}"),
                                false,
                                "editor"))
                .isEqualTo(p);
        var edited =
                candidates.basic(
                        p.id(),
                        p.version(),
                        json.readTree("{\"fullName\":\"Edited Person\"}"),
                        false,
                        "editor");
        assertThat(edited.basic().path("gender").asString()).isEqualTo("MALE");
        assertThat(
                        edited.extensions()
                                .path("personalInfo")
                                .path("fields")
                                .path("fullName")
                                .path("evidence")
                                .isEmpty())
                .isTrue();
        assertThat(
                        edited.extensions()
                                .path("personalInfo")
                                .path("fields")
                                .path("gender")
                                .path("evidence")
                                .isEmpty())
                .isFalse();
        var confirmed = candidates.basic(p.id(), edited.version(), null, true, "reviewer");
        assertThat(confirmed.extensions()).isEqualTo(edited.extensions());
        assertThat(candidates.basic(p.id(), confirmed.version(), null, true, "reviewer").version())
                .isEqualTo(confirmed.version());
    }

    @Test
    void protectedBasicCreatesTypedProposalAndAcceptPreservesSource() throws Exception {
        var d = document();
        publication.publish(result(d, "Full name: Synthetic Person\nGender: Male", false));
        var p = queries.detail(candidate(d));
        p =
                candidates.basic(
                        p.id(),
                        p.version(),
                        json.readTree("{\"fullName\":\"Edited Person\"}"),
                        false,
                        "editor");
        publication.publish(result(d, "Full name: Other Person\nGender: Female", false));
        var proposal = queries.proposals(p.id(), null, 100).getFirst();
        assertThat(proposal.kind()).isEqualTo("PERSONAL_INFO");
        var before = queries.detail(p.id());
        assertThat(before.basic().path("fullName").asString()).isEqualTo("Edited Person");
        var after =
                reviews.decide(
                        p.id(), before.version(), proposal.id(), "ACCEPT", null, null, "reviewer");
        assertThat(after.basic().path("fullName").asString()).isEqualTo("Other Person");
        assertThat(after.basicState()).isEqualTo("CONFIRMED");
    }

    @Test
    void unchangedRemapDoesNotCreateHistoryOrProposal() throws Exception {
        var d = document();
        String raw = "Full name: Synthetic Person\nGender: Male";
        publication.publish(result(d, raw, false));
        var p = queries.detail(candidate(d));
        publication.publish(result(d, raw, false));
        assertThat(queries.detail(p.id()).version()).isEqualTo(p.version());
        assertThat(queries.proposals(p.id(), null, 100)).isEmpty();
    }

    @Test
    void emptyAndUnknownOnlyDoNotManufactureIdentity() throws Exception {
        var d = document();
        long r = result(d, "Gender: Unspecified", false);
        assertThat(publication.publish(r)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                                Long.class,
                                d.getId()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                r))
                .isEqualTo("SKIPPED");
    }

    @Test
    void unknownGenderStaysInMetadataWithoutBecomingBinary() throws Exception {
        var d = document();
        publication.publish(result(d, "Full name: Synthetic Person\nGender: Unspecified", false));
        var p = queries.detail(candidate(d));
        assertThat(p.basic().has("gender")).isFalse();
        var field = p.extensions().path("personalInfo").path("fields").path("gender");
        assertThat(field.path("normalized").isNull()).isTrue();
        assertThat(field.path("alternatives").get(0).path("raw").asString())
                .isEqualTo("Unspecified");
    }

    @Test
    void lateResultCannotRollBackNewerPersonalInformation() throws Exception {
        var d = document();
        long old = result(d, "Full name: Older Person", false);
        long next = result(d, "Full name: Newer Person", false);
        publication.publish(next);
        var p = queries.detail(candidate(d));
        assertThat(publication.publish(old)).isTrue();
        assertThat(queries.detail(p.id())).isEqualTo(p);
        assertThat(queries.proposals(p.id(), null, 100)).isEmpty();
    }

    @Test
    void absentFieldsDoNotDeleteAndManualNullIsExplicit() throws Exception {
        var d = document();
        publication.publish(result(d, "Full name: Synthetic Person\nGender: Male", false));
        var p = queries.detail(candidate(d));
        publication.publish(result(d, "Full name: Synthetic Person", false));
        assertThat(queries.detail(p.id()).basic()).isEqualTo(p.basic());
        var edited =
                candidates.basic(
                        p.id(), p.version(), json.readTree("{\"gender\":null}"), false, "editor");
        assertThat(edited.basic().path("gender").isNull()).isTrue();
        assertThat(edited.basic().path("fullName")).isEqualTo(p.basic().path("fullName"));
        assertThatThrownBy(
                        () ->
                                candidates.basic(
                                        p.id(),
                                        p.version(),
                                        json.readTree("{\"gender\":\"FEMALE\"}"),
                                        false,
                                        "stale"))
                .isInstanceOf(CandidateFailure.class)
                .hasMessage("VERSION_CONFLICT");
    }

    @Test
    void personalProposalRejectAndEditUseTheirOwnPayload() throws Exception {
        var d = document();
        publication.publish(result(d, "Full name: First Person", false));
        var p = queries.detail(candidate(d));
        publication.publish(result(d, "Full name: Second Person", false));
        p = queries.detail(p.id());
        var proposal = queries.proposals(p.id(), null, 100).getFirst();
        var rejected =
                reviews.decide(
                        p.id(), p.version(), proposal.id(), "REJECT", null, null, "reviewer");
        assertThat(rejected.basic().path("fullName").asString()).isEqualTo("First Person");
        publication.publish(result(d, "Full name: Third Person", false));
        p = queries.detail(p.id());
        proposal = queries.proposals(p.id(), null, 100).getFirst();
        var edited =
                reviews.decide(
                        p.id(),
                        p.version(),
                        proposal.id(),
                        "EDIT",
                        null,
                        json.readTree("{\"fullName\":\"User Person\"}"),
                        "editor");
        assertThat(edited.basic().path("fullName").asString()).isEqualTo("User Person");
        assertThat(edited.basicOrigin()).isEqualTo("USER");
        assertThat(
                        edited.extensions()
                                .path("personalInfo")
                                .path("fields")
                                .path("fullName")
                                .has("evidence"))
                .isFalse();
    }

    @Test
    void mixedPublicationRollsBackBasicExperienceLedgerAndHistoryTogether() throws Exception {
        var d = document();
        long r =
                result(
                        d,
                        "Full name: Synthetic Mixed\nGender: Female\nExperience\nCompany: Synthetic Workshop\nTitle: Engineer\nPeriod: 2020 - 2022",
                        false);
        long profilesBefore =
                jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATE_PROFILES\"", Long.class);
        long experiencesBefore =
                jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATE_EXPERIENCES\"", Long.class);
        jdbc.execute(
                "ALTER TABLE \"CANDIDATE_HISTORY\" ADD CONSTRAINT \"TEST_FAIL_PERSONAL_HISTORY\" CHECK (\"ACTION\"<>'PUBLISH_MAPPING') NOT VALID");
        try {
            assertThatThrownBy(() -> publication.publish(r))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(candidate(d)).isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                    Long.class,
                                    r))
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
                    "ALTER TABLE \"CANDIDATE_HISTORY\" DROP CONSTRAINT \"TEST_FAIL_PERSONAL_HISTORY\"");
        }
        assertThat(publication.publish(r)).isTrue();
        var profile = queries.detail(candidate(d));
        assertThat(profile.experiences()).hasSize(1);
        assertThat(profile.basic().path("gender").asString()).isEqualTo("FEMALE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"FULL_NAME\" FROM \"CANDIDATE_PROFILES\" WHERE \"CANDIDATE_ID\"=?",
                                String.class,
                                profile.id()))
                .isEqualTo(profile.basic().path("fullName").asString());
        var audit = revisions.history(profile.id(), null, 100).getFirst();
        assertThat(audit.actor()).isEqualTo("SYSTEM");
        assertThat(audit.action()).isEqualTo("PUBLISH_MAPPING");
        assertThat(audit.version()).isEqualTo(profile.version());
        assertThat(audit.after().path("basic")).isEqualTo(profile.basic());
        assertThat(audit.at()).isNotBlank();
    }

    @Test
    void duplicatePersonalProposalDoesNotAdvanceHistoryOrReplaceOtherExtensions() throws Exception {
        var d = document();
        publication.publish(result(d, "Full name: First Person\nGender: Male", false));
        var p = queries.detail(candidate(d));
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"EXTENSIONS\"=\"EXTENSIONS\" || '{\"syntheticOther\":{\"retained\":true}}'::jsonb WHERE \"CANDIDATE_ID\"=?",
                p.id());
        p = candidates.basic(p.id(), p.version(), null, true, "reviewer");
        publication.publish(result(d, "Full name: Second Person\nGender: Female", false));
        var changed = queries.detail(p.id());
        var history = revisions.history(p.id(), null, 100);
        var proposals = queries.proposals(p.id(), null, 100);
        publication.publish(result(d, "Full name: Second Person\nGender: Female", false));
        assertThat(queries.proposals(p.id(), null, 100)).isEqualTo(proposals);
        assertThat(revisions.history(p.id(), null, 100)).isEqualTo(history);
        assertThat(queries.detail(p.id())).isEqualTo(changed);
        assertThat(changed.extensions().path("syntheticOther").path("retained").asBoolean())
                .isTrue();
        assertThat(changed.basic().path("fullName").asString()).isEqualTo("First Person");
    }

    @Test
    void invalidPersonalDecisionTargetRollsBackWithoutClosingProposal() throws Exception {
        var d = document();
        publication.publish(result(d, "Full name: First Person", false));
        var p = queries.detail(candidate(d));
        publication.publish(result(d, "Full name: Next Person", false));
        p = queries.detail(p.id());
        var proposal = queries.proposals(p.id(), null, 100).getFirst();
        var before = p;
        var history = revisions.history(p.id(), null, 100);
        assertThatThrownBy(
                        () ->
                                reviews.decide(
                                        before.id(),
                                        before.version(),
                                        proposal.id(),
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        null,
                                        "reviewer"))
                .isInstanceOf(CandidateFailure.class)
                .hasMessage("VALIDATION_ERROR");
        assertThat(queries.detail(p.id())).isEqualTo(before);
        assertThat(queries.proposals(p.id(), null, 100)).containsExactly(proposal);
        assertThat(revisions.history(p.id(), null, 100)).isEqualTo(history);
    }

    @Test
    void summaryInternalLineBreakChangesAreNotNoOpsForEditOrRemap() throws Exception {
        var d = document();
        String raw =
                "Full name: Synthetic Summary\nSummary: First sentence.\nSecond sentence.\nEducation\nSynthetic School";
        publication.publish(result(d, raw, false));
        var before = queries.detail(candidate(d));
        assertThat(before.basic().path("summary").asString())
                .isEqualTo("First sentence.\nSecond sentence.");
        var edited =
                candidates.basic(
                        before.id(),
                        before.version(),
                        json.readTree("{\"summary\":\"First sentence. Second sentence.\"}"),
                        false,
                        "editor");
        assertThat(edited.version()).isEqualTo(before.version() + 1);
        assertThat(edited.basic().path("summary").asString())
                .isEqualTo("First sentence. Second sentence.");
        publication.publish(result(d, raw, false));
        var proposals = queries.proposals(before.id(), null, 100);
        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().data().path("patch").path("summary").asString())
                .isEqualTo("First sentence.\nSecond sentence.");
        assertThat(
                        com.cvcompass.backend.candidate.mapping.PersonalInfoMapper.same(
                                "summary",
                                tools.jackson.databind.node.StringNode.valueOf("First\r\nSecond"),
                                "  First\nSecond  "))
                .isTrue();
        assertThat(
                        com.cvcompass.backend.candidate.mapping.PersonalInfoMapper.same(
                                "summary",
                                json.getNodeFactory().stringNode("First  Second"),
                                "First Second"))
                .isFalse();
    }

    @Test
    void malformedSupportedEnvelopeIsSkippedOnceWithoutCreatingCandidate() throws Exception {
        var d = document();
        long r = result(d, "Full name: Synthetic Person", false);
        jdbc.update(
                "UPDATE \"CV_MAPPING_RESULTS\" SET \"RESULT\"=jsonb_set(\"RESULT\", '{experiences}', '\"invalid array\"'::jsonb) WHERE \"ID\"=?",
                r);
        assertThat(publication.publish(r)).isTrue();
        assertThat(candidate(d)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"STATUS\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                r))
                .isEqualTo("SKIPPED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"REASON_CODE\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                r))
                .isEqualTo("INVALID_MAPPING_ENVELOPE");
        assertThat(publication.publish(r)).isFalse();
    }

    @Test
    void skippedPublicationPersistsFiniteReasonWithoutAProfile() throws Exception {
        var d = document();
        long r = result(d, "Gender: Unspecified", false);
        assertThat(publication.publish(r)).isTrue();
        assertThat(candidate(d)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"REASON_CODE\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                r))
                .isEqualTo("NO_USABLE_FIELDS");
        assertThat(publication.publish(r)).isFalse();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CANDIDATE_PUBLICATIONS\" SET \"REASON_CODE\"='FREE_FORM' WHERE \"RESULT_ID\"=?",
                                        r))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE \"CANDIDATE_PUBLICATIONS\" SET \"STATUS\"='SUPERSEDED' WHERE \"RESULT_ID\"=?",
                                        r))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void legacySkippedLedgerWithoutReasonRemainsAnImmutableReplay() throws Exception {
        var d = document();
        long r = result(d, "Gender: Unspecified", false);
        var source =
                new com.cvcompass.backend.mapping.repository.CompletedMappingRepository(jdbc)
                        .read(r)
                        .orElseThrow();
        // A pre-V12 ledger row has no diagnostic; replay must not invent its historical reason.
        jdbc.update(
                "INSERT INTO \"CANDIDATE_PUBLICATIONS\" (\"RESULT_ID\",\"DOCUMENT_ID\",\"EXTRACTION_ID\",\"CONFIG_ID\",\"STATUS\") VALUES (?,?,?,?,'SKIPPED')",
                r,
                source.documentId(),
                source.extractionId(),
                source.configId());
        assertThat(publication.publish(r)).isFalse();
        assertThat(candidate(d)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"REASON_CODE\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                r))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                Integer.class,
                                r))
                .isEqualTo(1);
    }

    @Test
    void newSkippedLedgerRequiresSafeReasonBeforeAnyWrite() throws Exception {
        var d = document();
        long r = result(d, "Gender: Unspecified", false);
        var source =
                new com.cvcompass.backend.mapping.repository.CompletedMappingRepository(jdbc)
                        .read(r)
                        .orElseThrow();
        var repository = new com.cvcompass.backend.candidate.repository.PublicationRepository(jdbc);
        assertThatThrownBy(() -> repository.ledger(source, null, "SKIPPED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid publication reason");
        assertThatThrownBy(() -> repository.ledger(source, null, "SKIPPED", "UNTRUSTED_DIAGNOSTIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid publication reason");
        assertThatThrownBy(() -> repository.ledger(source, null, "SUPERSEDED", "NO_USABLE_FIELDS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid publication reason");
        assertThat(repository.exists(r)).isFalse();
        assertThat(publication.publish(r)).isTrue();
        assertThat(publication.publish(r)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"REASON_CODE\" FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?",
                                String.class,
                                r))
                .isEqualTo("NO_USABLE_FIELDS");
    }
}
