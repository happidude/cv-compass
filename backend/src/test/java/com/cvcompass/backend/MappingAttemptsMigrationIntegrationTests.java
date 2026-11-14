package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Independent check of the V13 constraints; uses only synthetic rows in a private schema. */
class MappingAttemptsMigrationIntegrationTests {
    private static final String FINISHED = "TIMESTAMPTZ '2026-01-01 00:01:00+00'";
    private static final String OUTCOME = "'" + "c".repeat(64) + "'";
    private static IsolatedPostgresSchema database;
    private static SingleConnectionDataSource source;
    private static JdbcTemplate jdbc;
    private static long job;
    private static long secondJob;

    @BeforeAll
    static void migrate() throws Exception {
        database = new IsolatedPostgresSchema();
        Map<String, Object> properties = new java.util.HashMap<>();
        database.configure((name, supplier) -> properties.put(name, supplier.get()));
        var plain =
                new DriverManagerDataSource(
                        properties.get("spring.datasource.url").toString(),
                        properties.get("spring.datasource.username").toString(),
                        properties.get("spring.datasource.password").toString());
        var flyway =
                Flyway.configure()
                        .dataSource(plain)
                        .defaultSchema(database.schemaName())
                        .schemas(database.schemaName())
                        .load();
        flyway.migrate();
        flyway.validate();
        // Autocommit stays on, so every rejected statement is independent.
        source = new SingleConnectionDataSource(plain.getConnection(), true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("SET search_path TO " + database.schemaName());
        long document =
                jdbc.queryForObject(
                        "INSERT INTO \"CV_DOCUMENTS\" (\"ORIGINAL_FILENAME\",\"STORAGE_PATH\",\"FILE_SIZE\",\"MIME_TYPE\",\"SHA256\",\"STATUS\") VALUES ('synthetic.pdf','synthetic.pdf',1,'application/pdf',?,'COMPLETED') RETURNING \"ID\"",
                        Long.class,
                        "a".repeat(64));
        long processing =
                jdbc.queryForObject(
                        "INSERT INTO \"CV_PROCESSING_JOBS\" (\"CV_DOCUMENT_ID\",\"STATUS\") VALUES (?,'COMPLETED') RETURNING \"ID\"",
                        Long.class,
                        document);
        long extraction =
                jdbc.queryForObject(
                        "INSERT INTO \"CV_EXTRACTIONS\" (\"CV_DOCUMENT_ID\",\"PROCESSING_JOB_ID\",\"RAW_TEXT\",\"STRUCTURED_DATA\",\"EXTRACTION_METHOD\") VALUES (?,?,'Synthetic Person','{}'::jsonb,'TEXT_EXTRACTION') RETURNING \"ID\"",
                        Long.class,
                        document,
                        processing);
        long config =
                jdbc.queryForObject(
                        "INSERT INTO \"CV_MAPPING_CONFIGS\" (\"REVISION\",\"DEFINITION\") VALUES ('synthetic-rev','{}'::jsonb) RETURNING \"ID\"",
                        Long.class);
        job = mappingJob(extraction, config, "llm-extraction/1.0");
        secondJob = mappingJob(extraction, config, "synthetic-other/1.0");
    }

    private static long mappingJob(long extraction, long config, String engine) {
        return jdbc.queryForObject(
                "INSERT INTO \"CV_MAPPING_JOBS\" (\"EXTRACTION_ID\",\"CONFIG_ID\",\"ENGINE_VERSION\") VALUES (?,?,?) RETURNING \"ID\"",
                Long.class,
                extraction,
                config,
                engine);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (source != null) source.destroy();
        if (database != null) {
            try (var connection =
                            java.sql.DriverManager.getConnection(
                                    System.getenv()
                                            .getOrDefault(
                                                    "TEST_DB_URL",
                                                    "jdbc:postgresql://localhost:5432/cv_compass"),
                                    System.getenv().getOrDefault("TEST_DB_USERNAME", "cvcompass"),
                                    System.getenv("TEST_DB_PASSWORD"));
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + database.schemaName() + " CASCADE");
            }
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM \"CV_MAPPING_ATTEMPTS\"");
    }

    private static Map<String, String> valid(long jobId) {
        var row = new LinkedHashMap<String, String>();
        row.put("PUBLIC_ID", "gen_random_uuid()");
        row.put("JOB_ID", Long.toString(jobId));
        row.put("ATTEMPT_NO", "1");
        row.put("WORKER_ID", "'synthetic_worker'");
        row.put("CLAIM_REQUEST_ID", "gen_random_uuid()");
        row.put("CLAIM_BODY_SHA256", "'" + "a".repeat(64) + "'");
        row.put("LEASE_TOKEN_HASH", "'" + "b".repeat(64) + "'");
        row.put("STARTED_AT", "TIMESTAMPTZ '2026-01-01 00:00:00+00'");
        row.put("LEASE_EXPIRES_AT", "TIMESTAMPTZ '2026-01-01 00:02:00+00'");
        row.put("HARD_DEADLINE_AT", "TIMESTAMPTZ '2026-01-01 00:15:00+00'");
        row.put("RECLAIM_AFTER", "TIMESTAMPTZ '2026-01-01 00:17:00+00'");
        row.put("STATUS", "'ACTIVE'");
        row.put("PROVENANCE", "'{}'::jsonb");
        return row;
    }

    private static Map<String, String> failed(Map<String, String> row) {
        return with(
                row,
                "STATUS",
                "'FAILED'",
                "FINISHED_AT",
                FINISHED,
                "END_REASON",
                "'WORKER_FAILED'",
                "ERROR_CODE",
                "'PROVIDER_ERROR'");
    }

    private static Map<String, String> accepted(Map<String, String> row) {
        return with(
                row,
                "STATUS",
                "'ACCEPTED'",
                "FINISHED_AT",
                FINISHED,
                "END_REASON",
                "'RESULT_ACCEPTED'",
                "OUTCOME_SHA256",
                OUTCOME);
    }

    private static int insert(Map<String, String> row) {
        return jdbc.update(
                "INSERT INTO \"CV_MAPPING_ATTEMPTS\" (\""
                        + String.join("\",\"", row.keySet())
                        + "\") VALUES ("
                        + String.join(",", row.values())
                        + ")");
    }

    private static Map<String, String> with(Map<String, String> row, String... overrides) {
        for (int i = 0; i < overrides.length; i += 2) row.put(overrides[i], overrides[i + 1]);
        return row;
    }

    private static void rejected(String why, Map<String, String> row) {
        assertThatThrownBy(() -> insert(row)).as(why).isInstanceOf(DataAccessException.class);
    }

    @Test
    void baselineRowAndEveryTerminalShapeAreAccepted() {
        assertThat(insert(valid(job))).isEqualTo(1);
        jdbc.update("DELETE FROM \"CV_MAPPING_ATTEMPTS\"");
        insert(accepted(valid(job)));
        insert(with(failed(valid(job)), "ATTEMPT_NO", "2"));
        insert(
                with(
                        valid(job),
                        "ATTEMPT_NO",
                        "3",
                        "STATUS",
                        "'EXPIRED'",
                        "FINISHED_AT",
                        FINISHED,
                        "END_REASON",
                        "'LEASE_EXPIRED'",
                        "ERROR_CODE",
                        "'LEASE_EXPIRED'"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_MAPPING_ATTEMPTS\"", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void timeOrderingIsEnforced() {
        rejected(
                "started == lease",
                with(valid(job), "LEASE_EXPIRES_AT", "TIMESTAMPTZ '2026-01-01 00:00:00+00'"));
        rejected(
                "lease after deadline",
                with(valid(job), "LEASE_EXPIRES_AT", "TIMESTAMPTZ '2026-01-01 00:16:00+00'"));
        rejected(
                "deadline == reclaim",
                with(valid(job), "RECLAIM_AFTER", "TIMESTAMPTZ '2026-01-01 00:15:00+00'"));
        rejected(
                "finished before started",
                with(failed(valid(job)), "FINISHED_AT", "TIMESTAMPTZ '2025-12-31 23:59:00+00'"));
        // lease == deadline is the documented boundary and must stay legal.
        insert(with(valid(job), "LEASE_EXPIRES_AT", "TIMESTAMPTZ '2026-01-01 00:15:00+00'"));
    }

    @Test
    void statusAndTerminalShapeAreEnforced() {
        rejected("unknown status", with(valid(job), "STATUS", "'RUNNING'"));
        rejected("ACTIVE with FINISHED_AT", with(valid(job), "FINISHED_AT", FINISHED));
        rejected("ACTIVE with ERROR_CODE", with(valid(job), "ERROR_CODE", "'X'"));
        rejected("ACTIVE with END_REASON", with(valid(job), "END_REASON", "'X'"));
        rejected("ACTIVE with OUTCOME", with(valid(job), "OUTCOME_SHA256", OUTCOME));
        rejected("ACCEPTED without OUTCOME", with(accepted(valid(job)), "OUTCOME_SHA256", "NULL"));
        rejected("ACCEPTED wrong reason", with(accepted(valid(job)), "END_REASON", "'OTHER'"));
        rejected("ACCEPTED with ERROR_CODE", with(accepted(valid(job)), "ERROR_CODE", "'X'"));
        rejected("ACCEPTED without FINISHED_AT", with(accepted(valid(job)), "FINISHED_AT", "NULL"));
        rejected("FAILED without FINISHED_AT", with(failed(valid(job)), "FINISHED_AT", "NULL"));
        rejected("FAILED without ERROR_CODE", with(failed(valid(job)), "ERROR_CODE", "NULL"));
        rejected("FAILED without END_REASON", with(failed(valid(job)), "END_REASON", "NULL"));
        rejected(
                "EXPIRED with another code",
                with(
                        failed(valid(job)),
                        "STATUS",
                        "'EXPIRED'",
                        "END_REASON",
                        "'LEASE_EXPIRED'",
                        "ERROR_CODE",
                        "'X'"));
        rejected(
                "EXPIRED with outcome",
                with(
                        failed(valid(job)),
                        "STATUS",
                        "'EXPIRED'",
                        "END_REASON",
                        "'LEASE_EXPIRED'",
                        "ERROR_CODE",
                        "'LEASE_EXPIRED'",
                        "OUTCOME_SHA256",
                        OUTCOME));
    }

    @Test
    void hashesWorkerAttemptNumberAndProvenanceAreValidated() {
        rejected(
                "non hex claim hash",
                with(valid(job), "CLAIM_BODY_SHA256", "'" + "z".repeat(64) + "'"));
        rejected(
                "uppercase lease hash",
                with(valid(job), "LEASE_TOKEN_HASH", "'" + "B".repeat(64) + "'"));
        rejected(
                "non hex outcome",
                with(accepted(valid(job)), "OUTCOME_SHA256", "'" + "g".repeat(64) + "'"));
        rejected("worker with space", with(valid(job), "WORKER_ID", "'bad worker'"));
        rejected("worker starting with dash", with(valid(job), "WORKER_ID", "'-worker'"));
        rejected("empty worker", with(valid(job), "WORKER_ID", "''"));
        rejected("attempt zero", with(valid(job), "ATTEMPT_NO", "0"));
        rejected("provenance array", with(valid(job), "PROVENANCE", "'[]'::jsonb"));
        rejected("provenance null", with(valid(job), "PROVENANCE", "NULL"));
        insert(with(valid(job), "WORKER_ID", "'" + "w".repeat(128) + "'"));
    }

    @Test
    void uniquenessAndForeignKeyAreEnforced() {
        insert(valid(job));
        assertThatThrownBy(() -> insert(with(valid(job), "ATTEMPT_NO", "2")))
                .as("second ACTIVE on the same job")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(failed(valid(job))))
                .as("duplicate (JOB_ID, ATTEMPT_NO)")
                .isInstanceOf(DataIntegrityViolationException.class);
        // Once the active attempt ends, the next attempt number may become ACTIVE.
        jdbc.update(
                "UPDATE \"CV_MAPPING_ATTEMPTS\" SET \"STATUS\"='FAILED',\"FINISHED_AT\"="
                        + FINISHED
                        + ",\"END_REASON\"='X',\"ERROR_CODE\"='Y'");
        insert(with(valid(job), "ATTEMPT_NO", "2"));

        var fixed = "'00000000-0000-0000-0000-000000000001'::uuid";
        insert(with(valid(secondJob), "CLAIM_REQUEST_ID", fixed));
        assertThatThrownBy(
                        () ->
                                insert(
                                        with(
                                                failed(valid(secondJob)),
                                                "ATTEMPT_NO",
                                                "2",
                                                "CLAIM_REQUEST_ID",
                                                fixed)))
                .as("same (WORKER_ID, CLAIM_REQUEST_ID)")
                .isInstanceOf(DataIntegrityViolationException.class);
        // A different worker may reuse the request id.
        insert(
                with(
                        failed(valid(secondJob)),
                        "ATTEMPT_NO",
                        "3",
                        "WORKER_ID",
                        "'other_worker'",
                        "CLAIM_REQUEST_ID",
                        fixed));
        assertThatThrownBy(
                        () ->
                                insert(
                                        with(
                                                failed(valid(secondJob)),
                                                "PUBLIC_ID",
                                                "(SELECT \"PUBLIC_ID\" FROM \"CV_MAPPING_ATTEMPTS\" LIMIT 1)",
                                                "ATTEMPT_NO",
                                                "9")))
                .as("PUBLIC_ID unique")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(valid(-1)))
                .as("FK to job")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jobWithAttemptsCannotBeDeletedAndRequiredIndexesExist() {
        insert(valid(job));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM \"CV_MAPPING_JOBS\" WHERE \"ID\"=?", job))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE schemaname=current_schema() AND tablename='CV_MAPPING_ATTEMPTS'",
                                String.class))
                .contains("UQ_MAPPING_ACTIVE_ATTEMPT", "IX_MAPPING_ATTEMPT_EXPIRY");
    }
}
