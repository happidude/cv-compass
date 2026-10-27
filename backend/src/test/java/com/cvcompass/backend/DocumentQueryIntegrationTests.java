package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.ingestion.dto.OcrAttemptKey;
import com.cvcompass.backend.ingestion.dto.OcrClaimRequest;
import com.cvcompass.backend.ingestion.entity.CvDocument;
import com.cvcompass.backend.ingestion.entity.CvExtraction;
import com.cvcompass.backend.ingestion.entity.CvProcessingJob;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.enums.ExtractionMethod;
import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.ingestion.repository.CvExtractionRepository;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.service.DocumentQueryService;
import com.cvcompass.backend.ingestion.service.OcrJobService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;

/** Real PostgreSQL read-model checks; no synthetic successful OCR state. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "cv.ocr.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentQueryIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @Autowired DocumentQueryService service;
    @Autowired OcrJobService ocrJobs;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired CvExtractionRepository extractions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ownSchema() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(DATABASE.schemaName());
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(DocumentQueryIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    private CvDocument document() {
        var document = new CvDocument();
        document.setOriginalFilename("synthetic.pdf");
        document.setStoragePath(UUID.randomUUID().toString().replace("-", ""));
        document.setFileSize(100L);
        document.setMimeType("application/pdf");
        document.setSha256("a".repeat(64));
        document.setStatus(DocumentStatus.PENDING);
        return documents.saveAndFlush(document);
    }

    private CvProcessingJob job(CvDocument document, JobStatus status) {
        var job = new CvProcessingJob();
        job.setDocument(document);
        job.setStatus(status);
        return jobs.saveAndFlush(job);
    }

    @Test
    void cursorDoesNotShiftWhenNewDocumentsArriveOrTimestampsTie() {
        var first = document();
        var second = document();
        var third = document();
        jdbc.update(
                "UPDATE \"CV_DOCUMENTS\" SET \"CREATED_AT\"='2026-01-01T00:00:00Z' WHERE \"ID\" IN (?,?,?)",
                first.getId(),
                second.getId(),
                third.getId());
        var page = service.list(null, 2);
        assertThat(page.content())
                .extracting(row -> row.metadata().id())
                .containsExactly(third.getId(), second.getId());
        document();
        var next = service.list(page.nextBeforeId(), 2);
        assertThat(next.content().getFirst().metadata().id()).isEqualTo(first.getId());
        assertThat(next.content()).noneMatch(row -> row.metadata().id() >= second.getId());
    }

    @Test
    void noJobAndPendingJobAreDifferentFromIngestionStatus() {
        var document = document();
        assertThat(service.find(document.getId()).processing().status()).isEqualTo("NOT_SCHEDULED");
        job(document, JobStatus.PENDING);
        var detail = service.find(document.getId());
        assertThat(detail.ingestionStatus()).isEqualTo("STORED");
        assertThat(detail.processing().status()).isEqualTo("PENDING");
        assertThat(detail.ocr().availability()).isEqualTo("NONE");
    }

    @Test
    void newestFailedJobNeverReturnsAnOlderLegacyExtraction() {
        var document = document();
        var oldJob = job(document, JobStatus.FAILED);
        var extraction = new CvExtraction();
        extraction.setDocument(document);
        extraction.setProcessingJob(oldJob);
        extraction.setExtractionMethod(ExtractionMethod.NONE);
        extraction.setRawText("Synthetic legacy text");
        extractions.saveAndFlush(extraction);
        assertThat(service.find(document.getId()).ocr().availability()).isEqualTo("LEGACY");
        assertThat(service.find(document.getId()).ocr().legacyRawText())
                .isEqualTo("Synthetic legacy text");
        var newest = job(document, JobStatus.FAILED);
        var detail = service.find(document.getId());
        assertThat(detail.processing().jobId()).isEqualTo(newest.getId());
        assertThat(detail.processing().status()).isEqualTo("FAILED");
        assertThat(detail.ocr().availability()).isEqualTo("NONE");
        assertThat(detail.ocr().legacyRawText()).isNull();
    }

    @Test
    void deliveredFailedResultRemainsDistinctFromNoResultThroughRealSql() {
        var document = document();
        var job = new CvProcessingJob();
        job.setDocument(document);
        job.setStatus(JobStatus.PENDING);
        job.setProfileRevision("synthetic-" + UUID.randomUUID());
        job.setGeneration(1);
        job.setMaxAttempts(3);
        job.setAttemptsStarted(0);
        job.setNextAttemptAt(Instant.EPOCH);
        job.setAdmissionSnapshot(Map.of("synthetic_only", true));
        jobs.saveAndFlush(job);
        var request =
                new OcrClaimRequest(
                        "synthetic-worker",
                        UUID.randomUUID(),
                        "a".repeat(64),
                        job.getProfileRevision());
        var claim = ocrJobs.claim(request).orElseThrow();
        String payload =
                """
                {"protocol":"cv-compass.ocr-job/1.0","profile_revision":"%s","exit_code":2,"delivered":true,
                 "provenance":{"worker_commit":"%s","image_id":"sha256:%s"},
                 "ocr_result":{"type":"result","schema_version":"cv-compass.ocr/1.0","request_id":"a_%s",
                  "document_id":"%s","status":"failed","page_count":null,"pages":[],"warnings":[],
                  "errors":[{"code":"FILE_CORRUPT","stage":"preflight"}],
                  "timings_ms":{"preflight":1,"cleanup":1,"total":2},
                  "engines":{"python":null,"pdfplumber":null,"pdfminer_six":null,"pypdfium2":null,"pdfium":null,
                  "tesseract":null,"leptonica":null,"models":[],"pillow":null,"worker_version":null},
                  "config_fingerprint":null}}
                """
                        .formatted(
                                job.getProfileRevision(),
                                "a".repeat(40),
                                "b".repeat(64),
                                claim.attemptId().toString().replace("-", ""),
                                document.getId());
        ocrJobs.complete(
                new OcrAttemptKey(claim.attemptId(), request.workerId(), request.leaseTokenHash()),
                payload);
        var detail = service.find(document.getId());
        assertThat(detail.ingestionStatus()).isEqualTo("STORED");
        assertThat(detail.processing().status()).isEqualTo("FAILED");
        assertThat(detail.ocr().availability()).isEqualTo("PAGE_RESULT");
        assertThat(detail.ocr().status()).isEqualTo("failed");
        assertThat(detail.ocr().extractionId()).isNotNull();
        assertThat(detail.ocr().pageCount()).isNull();
        assertThat(detail.ocr().pages()).isEmpty();
        assertThat(detail.ocr().errors().getFirst().code()).isEqualTo("FILE_CORRUPT");
    }
}
