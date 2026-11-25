package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RemapMigrationIntegrationTests {
    @Test
    void upgradePreservesExistingJobsAddsDefaultsCommentsAndRemapConstraints() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var values = new HashMap<String, Object>();
            database.configure((key, supplier) -> values.put(key, supplier.get()));
            var source =
                    new DriverManagerDataSource(
                            (String) values.get("spring.datasource.url"),
                            (String) values.get("spring.datasource.username"),
                            (String) values.get("spring.datasource.password"));
            Flyway.configure().dataSource(source).target("16").load().migrate();
            var jdbc = new JdbcTemplate(source);
            long doc =
                    jdbc.queryForObject(
                            "INSERT INTO \"INGESTION\".\"DOCUMENTS\" (\"ORIGINAL_FILENAME\",\"STORAGE_PATH\",\"FILE_SIZE\",\"MIME_TYPE\",\"SHA256\",\"STATUS\") VALUES ('synthetic.pdf','synthetic.pdf',1,'application/pdf',?,'COMPLETED') RETURNING \"ID\"",
                            Long.class,
                            "a".repeat(64));
            long ocr =
                    jdbc.queryForObject(
                            "INSERT INTO \"INGESTION\".\"PROCESSING_JOBS\" (\"CV_DOCUMENT_ID\",\"STATUS\") VALUES (?,'PENDING') RETURNING \"ID\"",
                            Long.class,
                            doc);
            long extraction =
                    jdbc.queryForObject(
                            "INSERT INTO \"INGESTION\".\"EXTRACTIONS\" (\"CV_DOCUMENT_ID\",\"PROCESSING_JOB_ID\",\"EXTRACTION_METHOD\") VALUES (?,?,'OCR') RETURNING \"ID\"",
                            Long.class,
                            doc,
                            ocr);
            long config =
                    jdbc.queryForObject(
                            "INSERT INTO \"MAPPING\".\"CONFIGS\" (\"REVISION\",\"DEFINITION\") VALUES ('synthetic-migration','{}') RETURNING \"ID\"",
                            Long.class);
            long job =
                    jdbc.queryForObject(
                            "INSERT INTO \"MAPPING\".\"JOBS\" (\"EXTRACTION_ID\",\"CONFIG_ID\",\"ENGINE_VERSION\") VALUES (?,?,'synthetic-engine') RETURNING \"ID\"",
                            Long.class,
                            extraction,
                            config);
            String before =
                    jdbc.queryForObject(
                            "SELECT to_jsonb(j)::text FROM \"MAPPING\".\"JOBS\" j WHERE \"ID\"=?",
                            String.class,
                            job);
            var flyway = Flyway.configure().dataSource(source).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT (to_jsonb(j)-ARRAY['PRIORITY','REMAP_RUN_ID'])::text FROM \"MAPPING\".\"JOBS\" j WHERE \"ID\"=?",
                                    String.class,
                                    job))
                    .isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"PRIORITY\"=0 AND \"REMAP_RUN_ID\" IS NULL FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                    Boolean.class,
                                    job))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT indexdef FROM pg_indexes WHERE schemaname='MAPPING' AND indexname='IX_MAPPING_READY'",
                                    String.class))
                    .contains("\"PRIORITY\", \"NEXT_ATTEMPT_AT\", \"ID\"", "PENDING");
            assertThat(
                            jdbc.queryForObject(
                                    """
                    SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='MAPPING' AND a.attnum>0 AND NOT a.attisdropped
                      AND (c.relname='REMAP_RUNS' OR c.relname='JOBS' AND a.attname IN ('PRIORITY','REMAP_RUN_ID'))
                      AND col_description(c.oid,a.attnum) IS NOT NULL
                    """,
                                    Integer.class))
                    .isEqualTo(16);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT obj_description('\"MAPPING\".\"REMAP_RUNS\"'::regclass)",
                                    String.class))
                    .isNotBlank();
            long run =
                    jdbc.queryForObject(
                            "INSERT INTO \"MAPPING\".\"REMAP_RUNS\" (\"SCOPE_KIND\",\"SCOPE_VALUE\",\"CONFIG_ID\",\"UPPER_EXTRACTION_ID\",\"CREATED_BY\") VALUES ('ALL','{}',?,?,'synthetic-admin') RETURNING \"ID\"",
                            Long.class,
                            config,
                            extraction);
            jdbc.update(
                    "UPDATE \"MAPPING\".\"JOBS\" SET \"REMAP_RUN_ID\"=?,\"PRIORITY\"=10 WHERE \"ID\"=?",
                    run,
                    job);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"MAPPING\".\"REMAP_RUNS\" SET \"SELECTED\"=1 WHERE \"ID\"=?",
                                            run))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"MAPPING\".\"JOBS\" SET \"PRIORITY\"=-1 WHERE \"ID\"=?",
                                            job))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"MAPPING\".\"JOBS\" SET \"REMAP_RUN_ID\"=-1 WHERE \"ID\"=?",
                                            job))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
