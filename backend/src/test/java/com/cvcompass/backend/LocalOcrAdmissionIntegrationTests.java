package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.ingestion.dto.ImportFile;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.service.CvBatchService;
import com.cvcompass.backend.ingestion.service.CvDocumentService;
import com.cvcompass.backend.ingestion.service.DiskImportService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "cv.ocr.enabled=true",
            "cv.ocr.integration.enabled=true",
            "cv.ocr.integration.admission.mode=local",
            "cv.ocr.integration.admission.local-profile-revision=test-v1",
            "cv.ocr.integration.reaper-delay=1d",
            "cv.import.enabled=true",
            "cv.import.scan-interval=1d",
            "cv.import.stability-interval=1s"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalOcrAdmissionIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static Path inbox;
    private static final byte[] PDF =
            "%PDF-1.7\nOutside manifest synthetic\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
        inbox = Files.createTempDirectory("cv-local-admission-test-");
        registry.add("cv.import.source-directory", () -> inbox.toString());
        registry.add("cv.ocr.integration.namespace", () -> "local-admission-test");
        registry.add(
                "cv.ocr.integration.workers.worker.credential-sha256[0]", () -> "d".repeat(64));
        registry.add("cv.ocr.integration.workers.worker.profiles[0]", () -> "test-v1");
        registry.add("cv.ocr.integration.profiles.test-v1.worker-commit", () -> "a".repeat(40));
        registry.add(
                "cv.ocr.integration.profiles.test-v1.image-id", () -> "sha256:" + "b".repeat(64));
        registry.add(
                "cv.ocr.integration.profiles.test-v1.config-fingerprint", () -> "c".repeat(64));
        registry.add(
                "cv.ocr.integration.profiles.test-v1.engines-json",
                OcrWorkerIntegrationTests::engines);
    }

    @Autowired CvDocumentService uploads;
    @Autowired CvBatchService batches;
    @Autowired DiskImportService importer;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean(name = "diskImportClock")
    Clock diskClock;

    @Test
    void everyIngestionPipelineEnqueuesOnceWithoutAManifest() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(DATABASE.databaseName());
        uploads.upload("single-synthetic.pdf", new ByteArrayInputStream(PDF));
        batches.upload(
                List.of(
                        new ImportFile(
                                "batch-one.pdf",
                                PDF.length,
                                () -> new ByteArrayInputStream(PDF),
                                null),
                        new ImportFile(
                                "batch-two.pdf",
                                PDF.length,
                                () -> new ByteArrayInputStream(PDF),
                                null)));
        when(diskClock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        Files.write(inbox.resolve("disk-synthetic.pdf"), PDF);
        importer.tick();
        when(diskClock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:02Z"));
        importer.tick();
        importer.tick();
        assertThat(jobs.findAll())
                .hasSize(4)
                .allSatisfy(
                        job -> {
                            assertThat(job.getProfileRevision()).isEqualTo("test-v1");
                            assertThat(job.getAdmissionSnapshot())
                                    .containsEntry("admission_mode", "local")
                                    .doesNotContainKey("fixture_id");
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT \"CV_DOCUMENT_ID\") FROM \"INGESTION\".\"PROCESSING_JOBS\"",
                                Integer.class))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"INGESTION\".\"EXTRACTIONS\"",
                                Integer.class))
                .isZero();
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(LocalOcrAdmissionIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        if (inbox != null)
            try (var paths = Files.walk(inbox)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        if (DATABASE.storageRoot() != null)
            try (var entries = Files.list(DATABASE.storageRoot())) {
                for (var path : entries.toList()) Files.delete(path);
            }
        DATABASE.close();
    }
}
