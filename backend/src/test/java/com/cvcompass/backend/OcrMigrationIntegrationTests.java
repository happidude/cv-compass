package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OcrMigrationIntegrationTests {
    @Test
    void v6PreservesLegacyJobsExtractionsAndCompositeForeignKey() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            Map<String, Object> properties = new HashMap<>();
            database.configure((name, supplier) -> properties.put(name, supplier.get()));
            var source =
                    new DriverManagerDataSource(
                            properties.get("spring.datasource.url").toString(),
                            properties.get("spring.datasource.username").toString(),
                            properties.get("spring.datasource.password").toString());
            Flyway.configure()
                    .dataSource(source)
                    .defaultSchema(database.schemaName())
                    .schemas(database.schemaName())
                    .target("5")
                    .load()
                    .migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("SET search_path TO " + database.schemaName());
            // JdbcTemplate obtains fresh connections, so qualify the isolated schema explicitly.
            String prefix = database.schemaName() + ".";
            long document =
                    jdbc.queryForObject(
                            "INSERT INTO "
                                    + prefix
                                    + "\"CV_DOCUMENTS\" (\"ORIGINAL_FILENAME\",\"STORAGE_PATH\",\"FILE_SIZE\",\"MIME_TYPE\",\"SHA256\",\"STATUS\") VALUES ('synthetic.pdf','synthetic.pdf',1,'application/pdf',?,'COMPLETED') RETURNING \"ID\"",
                            Long.class,
                            "a".repeat(64));
            long job =
                    jdbc.queryForObject(
                            "INSERT INTO "
                                    + prefix
                                    + "\"CV_PROCESSING_JOBS\" (\"CV_DOCUMENT_ID\",\"STATUS\",\"RETRY_COUNT\",\"ERROR_MESSAGE\") VALUES (?,'COMPLETED',7,'legacy marker') RETURNING \"ID\"",
                            Long.class,
                            document);
            long extraction =
                    jdbc.queryForObject(
                            "INSERT INTO "
                                    + prefix
                                    + "\"CV_EXTRACTIONS\" (\"CV_DOCUMENT_ID\",\"PROCESSING_JOB_ID\",\"RAW_TEXT\",\"STRUCTURED_DATA\",\"EXTRACTION_METHOD\") VALUES (?,?,'Synthetic legacy','{}'::jsonb,'TEXT_EXTRACTION') RETURNING \"ID\"",
                            Long.class,
                            document,
                            job);
            Flyway finalFlyway =
                    Flyway.configure()
                            .dataSource(source)
                            .defaultSchema(database.schemaName())
                            .schemas(database.schemaName())
                            .load();
            assertThat(finalFlyway.migrate().migrationsExecuted).isEqualTo(8);
            finalFlyway.validate();
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT \"RETRY_COUNT\",\"ERROR_MESSAGE\",\"PROFILE_REVISION\" FROM "
                                            + prefix
                                            + "\"CV_PROCESSING_JOBS\" WHERE \"ID\"=?",
                                    job))
                    .containsEntry("RETRY_COUNT", 7)
                    .containsEntry("ERROR_MESSAGE", "legacy marker")
                    .containsEntry("PROFILE_REVISION", null);
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT \"RAW_TEXT\",\"STRUCTURED_DATA\"::text,\"ATTEMPT_ID\",\"OCR_RESULT\" FROM "
                                            + prefix
                                            + "\"CV_EXTRACTIONS\" WHERE \"ID\"=?",
                                    extraction))
                    .containsEntry("RAW_TEXT", "Synthetic legacy")
                    .containsEntry("STRUCTURED_DATA", "{}")
                    .containsEntry("ATTEMPT_ID", null)
                    .containsEntry("OCR_RESULT", null);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_constraint WHERE connamespace=?::regnamespace AND conname='FK_EXTRACTION_JOB_DOCUMENT'",
                                    Integer.class,
                                    database.schemaName()))
                    .isEqualTo(1);
        }
    }
}
