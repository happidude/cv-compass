package com.cvcompass.backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import com.cvcompass.backend.ingestion.entity.*;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RepositoryPersistenceIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();

    // This class declares its own method so Spring cannot reuse another class's context.
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void removeIsolatedSchema() throws Exception {
        new org.springframework.test.context.TestContextManager(RepositoryPersistenceIntegrationTests.class).getTestContext()
                .markApplicationContextDirty(org.springframework.test.annotation.DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired CvImportBatchRepository batches;
    @Autowired CandidateRepository candidates;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired CvExtractionRepository extractions;

    @BeforeEach
    void usesItsOwnSchema() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(DATABASE.schemaName());
    }

    @Test
    void batchRoundTripsDefaultsStatusAndTimestamps() {
        var batch = batch();
        batch.setCompletedAt(Instant.parse("2026-01-01T00:00:00Z"));
        batches.saveAndFlush(batch);
        entityManager.clear();
        var loaded = batches.findById(batch.getId()).orElseThrow();
        assertThat(loaded.getTotalFiles()).isEqualTo(11);
        assertThat(loaded.getSuccessCount()).isZero();
        assertThat(loaded.getFailedCount()).isZero();
        assertThat(loaded.getStatus()).isEqualTo(BatchStatus.PENDING);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getCompletedAt()).isEqualTo(batch.getCompletedAt());
    }

    @Test
    void candidateRoundTripsNullableAndTextFields() {
        var candidate = new Candidate();
        candidate.setFullName("Synthetic fixture");
        candidate.setEmail("fixture@example.invalid");
        candidate.setPhone("synthetic");
        candidate.setLocation("Test location");
        candidate.setSummary("Synthetic summary");
        candidate.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        candidates.saveAndFlush(candidate);
        entityManager.clear();
        var loaded = candidates.findById(candidate.getId()).orElseThrow();
        assertThat(loaded.getFullName()).isEqualTo(candidate.getFullName());
        assertThat(loaded.getEmail()).isEqualTo(candidate.getEmail());
        assertThat(loaded.getPhone()).isEqualTo(candidate.getPhone());
        assertThat(loaded.getLocation()).isEqualTo(candidate.getLocation());
        assertThat(loaded.getSummary()).isEqualTo(candidate.getSummary());
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(candidate.getUpdatedAt());
        var unknown = candidates.saveAndFlush(new Candidate());
        entityManager.clear();
        assertThat(candidates.findById(unknown.getId()).orElseThrow().getFullName()).isNull();
    }

    @Test
    void candidateUpdateRetainsInheritedIdentityAndCreationTime() {
        var candidate = candidates.saveAndFlush(new Candidate());
        var id = candidate.getId();
        var createdAt = candidate.getCreatedAt();
        entityManager.clear();
        var loaded = candidates.findById(id).orElseThrow();
        loaded.setSummary("Updated synthetic summary");
        loaded.setUpdatedAt(Instant.parse("2026-10-14T00:00:00Z"));
        candidates.saveAndFlush(loaded);
        entityManager.clear();
        var updated = candidates.findById(id).orElseThrow();
        assertThat(updated.getId()).isEqualTo(id);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(updated.getSummary()).isEqualTo("Updated synthetic summary");
    }

    @Test
    void documentsRoundTripWithAndWithoutCandidateAndLazyLinks() {
        var batch = batches.saveAndFlush(batch());
        var candidate = candidates.saveAndFlush(new Candidate());
        var assigned = document(batch);
        assigned.setCandidate(candidate);
        documents.saveAndFlush(assigned);
        var unassigned = documents.saveAndFlush(document(batch));
        entityManager.clear();
        var loaded = documents.findById(assigned.getId()).orElseThrow();
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(loaded, "batch")).isFalse();
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(loaded, "candidate")).isFalse();
        assertThat(loaded.getBatch().getId()).isEqualTo(batch.getId());
        assertThat(loaded.getCandidate().getId()).isEqualTo(candidate.getId());
        assertThat(loaded.getOriginalFilename()).isEqualTo("fixture.pdf");
        assertThat(loaded.getStoragePath()).isEqualTo("test/fixture");
        assertThat(loaded.getFileSize()).isEqualTo(0L);
        assertThat(loaded.getMimeType()).isEqualTo("application/pdf");
        assertThat(loaded.getSha256()).isEqualTo("a".repeat(64));
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(documents.findById(unassigned.getId()).orElseThrow().getCandidate()).isNull();
    }

    @Test
    void jobAndExtractionRoundTripNestedJsonAndMatchingDocument() {
        var document = documents.saveAndFlush(document(batches.saveAndFlush(batch())));
        var job = job(document);
        job.setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
        job.setCompletedAt(Instant.parse("2026-01-01T00:01:00Z"));
        job.setErrorMessage("Synthetic test message");
        jobs.saveAndFlush(job);
        var extraction = extraction(document, job);
        extraction.setRawText("Synthetic Vietnamese text: hồ sơ thử nghiệm");
        extraction.setStructuredData(Map.of("skills", List.of("Java", "Python"), "profile", Map.of("location", "Test location")));
        extractions.saveAndFlush(extraction);
        entityManager.clear();
        var loadedJob = jobs.findById(job.getId()).orElseThrow();
        assertThat(loadedJob.getDocument().getId()).isEqualTo(document.getId());
        assertThat(loadedJob.getRetryCount()).isZero();
        assertThat(loadedJob.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(loadedJob.getStartedAt()).isEqualTo(job.getStartedAt());
        assertThat(loadedJob.getCompletedAt()).isEqualTo(job.getCompletedAt());
        assertThat(loadedJob.getErrorMessage()).isEqualTo(job.getErrorMessage());
        assertThat(loadedJob.getCreatedAt()).isNotNull();
        entityManager.clear();
        var loaded = extractions.findById(extraction.getId()).orElseThrow();
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(loaded, "processingJob")).isFalse();
        assertThat(loaded.getDocument().getId()).isEqualTo(document.getId());
        assertThat(loaded.getProcessingJob().getDocument().getId()).isEqualTo(document.getId());
        assertThat(loaded.getRawText()).isEqualTo(extraction.getRawText());
        assertThat(loaded.getStructuredData()).isEqualTo(extraction.getStructuredData());
        assertThat(loaded.getExtractionMethod()).isEqualTo(ExtractionMethod.TEXT_EXTRACTION);
        assertThat(loaded.getCreatedAt()).isNotNull();
        var rerun = extractions.saveAndFlush(extraction(document, jobs.saveAndFlush(job(document))));
        entityManager.clear();
        assertThat(extractions.findById(rerun.getId()).orElseThrow().getStructuredData()).isNull();
        assertThat(extractions.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT pg_typeof(\"STRUCTURED_DATA\")::text FROM \"CV_EXTRACTIONS\" WHERE \"ID\" = ?", String.class, extraction.getId())).isEqualTo("jsonb");
    }

    @Test
    void databaseRejectsExtractionForAnotherJobsDocument() {
        var batch = batches.saveAndFlush(batch());
        var source = documents.saveAndFlush(document(batch));
        var other = documents.saveAndFlush(document(batch));
        var job = jobs.saveAndFlush(job(source));
        assertThatThrownBy(() -> extractions.saveAndFlush(extraction(other, job)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("FK_EXTRACTION_JOB_DOCUMENT");
    }

    @Test
    void batchCountersCannotExceedTotalFiles() {
        var batch = batch();
        batch.setSuccessCount(10);
        batch.setFailedCount(1);
        batches.saveAndFlush(batch); // Equality at the boundary is valid.
        batch.setFailedCount(2);
        assertThatThrownBy(() -> batches.saveAndFlush(batch))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CK_BATCH_COUNTS");
    }

    @Test
    void retryCountCannotBeNegative() {
        var document = documents.saveAndFlush(document(batches.saveAndFlush(batch())));
        var job = job(document);
        job.setRetryCount(-1);
        assertThatThrownBy(() -> jobs.saveAndFlush(job))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CK_JOB_RETRY_COUNT");
    }

    @ParameterizedTest
    @MethodSource("invalidSha256Values")
    void invalidSha256IsRejected(String digest) {
        var document = document(batches.saveAndFlush(batch()));
        document.setSha256(digest);
        assertThatThrownBy(() -> documents.saveAndFlush(document))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CK_DOCUMENT_SHA256");
    }

    static Stream<String> invalidSha256Values() {
        return Stream.of("", "a".repeat(63), "A".repeat(64), "g".repeat(64));
    }

    @ParameterizedTest
    @CsvSource({
            "CV_IMPORT_BATCHES, CK_BATCH_STATUS",
            "CV_DOCUMENTS, CK_DOCUMENT_STATUS",
            "CV_PROCESSING_JOBS, CK_JOB_STATUS"
    })
    void databaseRejectsInvalidStatus(String table, String constraint) {
        var batch = batches.saveAndFlush(batch());
        var document = documents.saveAndFlush(document(batch));
        var job = jobs.saveAndFlush(job(document));
        var id = switch (table) {
            case "CV_IMPORT_BATCHES" -> batch.getId();
            case "CV_DOCUMENTS" -> document.getId();
            case "CV_PROCESSING_JOBS" -> job.getId();
            default -> throw new IllegalArgumentException("Unexpected test table");
        };
        // Exercise the DB check directly because Java enums cannot represent invalid values.
        assertThatThrownBy(() -> jdbc.update("UPDATE \"" + table + "\" SET \"STATUS\" = ? WHERE \"ID\" = ?", "INVALID", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @ParameterizedTest
    @CsvSource({
            "CV_DOCUMENTS, BATCH_ID, FK_DOCUMENT_BATCH",
            "CV_DOCUMENTS, CANDIDATE_ID, FK_DOCUMENT_CANDIDATE",
            "CV_PROCESSING_JOBS, CV_DOCUMENT_ID, FK_JOB_DOCUMENT",
            "CV_EXTRACTIONS, CV_DOCUMENT_ID, FK_EXTRACTION_DOCUMENT",
            "CV_EXTRACTIONS, PROCESSING_JOB_ID, FK_EXTRACTION_JOB_DOCUMENT"
    })
    void foreignKeysRejectMissingReferences(String table, String column, String constraint) {
        var document = documents.saveAndFlush(document(batches.saveAndFlush(batch())));
        var job = jobs.saveAndFlush(job(document));

        var id = switch (table) {
            case "CV_DOCUMENTS" -> document.getId();
            case "CV_PROCESSING_JOBS" -> job.getId();
            case "CV_EXTRACTIONS" -> extractions.saveAndFlush(extraction(document, job)).getId();
            default -> throw new IllegalArgumentException("Unexpected test table");
        };
        // Identifier strings come exclusively from the fixed CsvSource above.
        assertThatThrownBy(() -> jdbc.update("UPDATE \"" + table + "\" SET \"" + column + "\" = ? WHERE \"ID\" = ?", Long.MAX_VALUE, id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @ParameterizedTest
    @CsvSource({
            "CV_IMPORT_BATCHES, TOTAL_FILES, 0, CK_BATCH_TOTAL_FILES",
            "CV_IMPORT_BATCHES, SUCCESS_COUNT, -1, CK_BATCH_SUCCESS_COUNT",
            "CV_IMPORT_BATCHES, FAILED_COUNT, -1, CK_BATCH_FAILED_COUNT",
            "CV_DOCUMENTS, FILE_SIZE, -1, CK_DOCUMENT_FILE_SIZE",
            "CV_EXTRACTIONS, EXTRACTION_METHOD, INVALID, CK_EXTRACTION_METHOD"
    })
    void databaseRejectsInvalidCountsSizesAndExtractionMethods(String table, String column,
                                                               String invalid, String constraint) {
        var batch = batches.saveAndFlush(batch());
        var document = documents.saveAndFlush(document(batch));
        var extraction = extractions.saveAndFlush(extraction(document, jobs.saveAndFlush(job(document))));
        long id = switch (table) {
            case "CV_IMPORT_BATCHES" -> batch.getId();
            case "CV_DOCUMENTS" -> document.getId();
            case "CV_EXTRACTIONS" -> extraction.getId();
            default -> throw new IllegalArgumentException("Unexpected test table");
        };
        Object value = column.equals("EXTRACTION_METHOD") ? invalid : Integer.valueOf(invalid);
        assertThatThrownBy(() -> jdbc.update("UPDATE \"" + table + "\" SET \"" + column + "\"=? WHERE \"ID\"=?", value, id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void importItemRequiresExactlyOneOutcome(boolean both) {
        var batch = batches.saveAndFlush(batch());
        var document = documents.saveAndFlush(document(batch));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO \"CV_IMPORT_ITEMS\" "
                + "(\"BATCH_ID\",\"FILE_ORDER\",\"DISPLAY_NAME\",\"DOCUMENT_ID\",\"ERROR_CODE\") VALUES (?,1,'fixture.pdf',?,?)",
                batch.getId(), both ? document.getId() : null, both ? "INVALID_PDF" : null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("CK_IMPORT_ITEM_RESULT");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"order", "duplicate", "batch", "document"})
    void importItemOrderAndReferencesAreProtected(String invalid) {
        var batch = batches.saveAndFlush(batch());
        jdbc.update("INSERT INTO \"CV_IMPORT_ITEMS\" (\"BATCH_ID\",\"FILE_ORDER\",\"DISPLAY_NAME\",\"ERROR_CODE\") "
                + "VALUES (?,1,'fixture.pdf','INVALID_PDF')", batch.getId());
        long batchId = invalid.equals("batch") ? Long.MAX_VALUE : batch.getId();
        int order = invalid.equals("order") ? 0 : invalid.equals("duplicate") ? 1 : 2;
        Long documentId = invalid.equals("document") ? Long.MAX_VALUE : null;
        String code = documentId == null ? "INVALID_PDF" : null;
        assertThatThrownBy(() -> jdbc.update("INSERT INTO \"CV_IMPORT_ITEMS\" "
                + "(\"BATCH_ID\",\"FILE_ORDER\",\"DISPLAY_NAME\",\"DOCUMENT_ID\",\"ERROR_CODE\") VALUES (?,?,'fixture.pdf',?,?)",
                batchId, order, documentId, code)).isInstanceOf(DataIntegrityViolationException.class);
    }
    private CvImportBatch batch() {
        var batch = new CvImportBatch();
        batch.setTotalFiles(11);
        batch.setStatus(BatchStatus.PENDING);
        return batch;
    }

    private CvDocument document(CvImportBatch batch) {
        var document = new CvDocument();
        document.setBatch(batch);
        document.setOriginalFilename("fixture.pdf");
        document.setStoragePath("test/fixture");
        document.setFileSize(0L);
        document.setMimeType("application/pdf");
        document.setSha256("a".repeat(64));
        document.setStatus(DocumentStatus.PENDING);
        return document;
    }

    private CvProcessingJob job(CvDocument document) {
        var job = new CvProcessingJob();
        job.setDocument(document);
        job.setStatus(JobStatus.PENDING);
        return job;
    }

    private CvExtraction extraction(CvDocument document, CvProcessingJob job) {
        var extraction = new CvExtraction();
        extraction.setDocument(document);
        extraction.setProcessingJob(job);
        extraction.setExtractionMethod(ExtractionMethod.TEXT_EXTRACTION);
        return extraction;
    }
}
