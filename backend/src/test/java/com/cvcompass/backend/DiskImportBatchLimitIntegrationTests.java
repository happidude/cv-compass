package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.ingestion.service.DiskImportService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
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

/** Real files/batch service/PostgreSQL history; synthetic PDFs, OCR remains disabled. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "cv.import.enabled=true",
            "cv.batch.max-files=5",
            "cv.import.scan-interval=1d",
            "cv.import.stability-interval=1s"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiskImportBatchLimitIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static Path inbox;

    @Autowired DiskImportService importer;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean(name = "diskImportClock")
    Clock clock;

    @DynamicPropertySource
    static void resources(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
        inbox = Files.createTempDirectory("cv-import-limit-test-");
        registry.add("cv.import.source-directory", () -> inbox.toString());
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(DiskImportBatchLimitIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        removeOwnedFiles(inbox);
        if (DATABASE.storageRoot() != null) {
            try (var files = Files.list(DATABASE.storageRoot())) {
                for (var file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        DATABASE.close();
    }

    private static void removeOwnedFiles(Path root) throws IOException {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void drainsTwelveFilesInFiveFiveAndTwoWithoutQueuedOrDuplicateImports() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(DATABASE.databaseName());
        for (int index = 1; index <= 12; index++) {
            Files.writeString(
                    inbox.resolve("synthetic-%02d.pdf".formatted(index)),
                    "%PDF-1.7\nsynthetic\n%%EOF\n");
        }
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        importer.tick();
        when(clock.instant()).thenReturn(now.plusSeconds(2));
        importer.tick();

        assertThat(
                        jdbc.queryForList(
                                "SELECT \"TOTAL_FILES\" FROM \"INGESTION\".\"IMPORT_BATCHES\" ORDER BY \"ID\"",
                                Integer.class))
                .containsExactly(5, 5, 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"INGESTION\".\"DOCUMENTS\"", Integer.class))
                .isEqualTo(12);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"INGESTION\".\"IMPORT_ITEMS\"",
                                Integer.class))
                .isEqualTo(12);
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"STATE\" FROM \"INGESTION\".\"DISK_IMPORT_FILES\"",
                                String.class))
                .hasSize(12)
                .containsOnly("PROCESSED");
        try (var files = Files.list(inbox.resolve("processed"))) {
            assertThat(files.count()).isEqualTo(12);
        }
        try (var files = Files.list(inbox)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".pdf")))
                    .isEmpty();
        }

        importer.tick();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"INGESTION\".\"IMPORT_BATCHES\"",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(\"ATTEMPTS\") FROM \"INGESTION\".\"DISK_IMPORT_FILES\"",
                                Integer.class))
                .isEqualTo(12);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"INGESTION\".\"PROCESSING_JOBS\"",
                                Integer.class))
                .isZero();
    }
}
