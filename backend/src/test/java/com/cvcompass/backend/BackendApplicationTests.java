package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.*;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Runs against PostgreSQL 17; requires TEST_DB_PASSWORD and schema creation permission. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendApplicationTests {
    private static final String SCHEMA = "cv_persistence_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/cv_compass");
    private static final String USER = System.getenv().getOrDefault("TEST_DB_USERNAME", "cvcompass");
    private static final String PASSWORD = System.getenv("TEST_DB_PASSWORD");
    private static boolean schemaCreated;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        if (PASSWORD == null || PASSWORD.isBlank()) {
            throw new IllegalStateException("Set TEST_DB_PASSWORD for isolated PostgreSQL integration tests");
        }
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void removeIsolatedSchema() throws Exception {
        if (schemaCreated) {
            try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    @Autowired EntityManager entityManager;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired CvImportBatchRepository batches;
    @Autowired CandidateRepository candidates;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired CvExtractionRepository extractions;

    @Test
    void contextStartsWithV1V2AndValidatedMappings() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                .containsExactly("1", "2");
        assertThat(entityManager.getMetamodel().getEntities()).extracting(e -> e.getName())
                .contains("CvImportBatch", "Candidate", "CvDocument", "CvProcessingJob", "CvExtraction");
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
