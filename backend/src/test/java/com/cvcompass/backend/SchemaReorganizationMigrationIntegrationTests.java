package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.identity.repository.UserRepository;
import com.cvcompass.backend.ingestion.repository.CandidateDocumentRepository;
import com.cvcompass.backend.ingestion.repository.DocumentQueryRepository;
import com.cvcompass.backend.ingestion.repository.OriginalPdfRepository;
import com.cvcompass.backend.ingestion.service.StoredOcrExtractionReader;
import com.cvcompass.backend.mapping.repository.CompletedMappingRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Synthetic upgrade coverage, independent of application startup and shared databases. */
class SchemaReorganizationMigrationIntegrationTests {
    private static final List<Table> TABLES =
            """
            CV_DOCUMENTS INGESTION DOCUMENTS
            CV_EXTRACTIONS INGESTION EXTRACTIONS
            CV_IMPORT_BATCHES INGESTION IMPORT_BATCHES
            CV_IMPORT_ITEMS INGESTION IMPORT_ITEMS
            CV_DISK_IMPORT_FILES INGESTION DISK_IMPORT_FILES
            CV_DISK_OBSERVATIONS INGESTION DISK_OBSERVATIONS
            CV_PROCESSING_JOBS INGESTION PROCESSING_JOBS
            CV_OCR_ATTEMPTS INGESTION OCR_ATTEMPTS
            CV_MAPPING_CONFIGS MAPPING CONFIGS
            CV_MAPPING_JOBS MAPPING JOBS
            CV_MAPPING_ATTEMPTS MAPPING ATTEMPTS
            CV_MAPPING_RESULTS MAPPING RESULTS
            CV_MAPPING_SETTINGS MAPPING SETTINGS
            CANDIDATES CANDIDATE CANDIDATES
            CANDIDATE_PROFILES CANDIDATE PROFILES
            CANDIDATE_EXPERIENCES CANDIDATE EXPERIENCES
            CANDIDATE_HISTORY CANDIDATE HISTORY
            CANDIDATE_PROPOSALS CANDIDATE PROPOSALS
            CANDIDATE_PUBLICATIONS CANDIDATE PUBLICATIONS
            CANDIDATE_PUBLICATION_SETTINGS CANDIDATE PUBLICATION_SETTINGS
            CV_USERS IDENTITY USERS
            """
                    .lines()
                    .map(line -> line.split(" "))
                    .map(parts -> new Table(parts[0], parts[1], parts[2]))
                    .toList();

    @Test
    void v14PreservesRowsRepositoryReadsIdentityForeignKeysAndTriggers() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var source = source(database);
            Flyway.configure().dataSource(source).target("13").load().migrate();
            var jdbc = new JdbcTemplate(source);

            // All unqualified legacy table names below are executed before V14.
            long candidate =
                    jdbc.queryForObject(
                            "INSERT INTO \"CANDIDATES\" (\"FULL_NAME\") VALUES ('Synthetic upgrade') RETURNING \"ID\"",
                            Long.class);
            jdbc.update(
                    "INSERT INTO \"CANDIDATE_PROFILES\" (\"CANDIDATE_ID\",\"FULL_NAME\",\"BASIC\") VALUES (?,'Synthetic upgrade','{\"fullName\":\"Synthetic upgrade\"}'::jsonb)",
                    candidate);
            long document =
                    jdbc.queryForObject(
                            "INSERT INTO \"CV_DOCUMENTS\" (\"CANDIDATE_ID\",\"ORIGINAL_FILENAME\",\"STORAGE_PATH\",\"FILE_SIZE\",\"MIME_TYPE\",\"SHA256\",\"STATUS\") VALUES (?,'synthetic-upgrade.pdf','synthetic-upgrade.pdf',1,'application/pdf',?,'COMPLETED') RETURNING \"ID\"",
                            Long.class,
                            candidate,
                            "a".repeat(64));
            long processing =
                    jdbc.queryForObject(
                            "INSERT INTO \"CV_PROCESSING_JOBS\" (\"CV_DOCUMENT_ID\",\"STATUS\") VALUES (?,'COMPLETED') RETURNING \"ID\"",
                            Long.class,
                            document);
            long extraction =
                    jdbc.queryForObject(
                            "INSERT INTO \"CV_EXTRACTIONS\" (\"CV_DOCUMENT_ID\",\"PROCESSING_JOB_ID\",\"RAW_TEXT\",\"STRUCTURED_DATA\",\"EXTRACTION_METHOD\") VALUES (?,?,'Synthetic legacy text','{}'::jsonb,'TEXT_EXTRACTION') RETURNING \"ID\"",
                            Long.class,
                            document,
                            processing);
            long config =
                    jdbc.queryForObject(
                            "INSERT INTO \"CV_MAPPING_CONFIGS\" (\"REVISION\",\"DEFINITION\",\"VALIDATION_STATE\",\"ACTIVATED_AT\",\"CONTENT_SHA256\") VALUES ('synthetic-upgrade','{}'::jsonb,'ACTIVE',CURRENT_TIMESTAMP,?) RETURNING \"ID\"",
                            Long.class,
                            "b".repeat(64));
            long mapping =
                    jdbc.queryForObject(
                            "INSERT INTO \"CV_MAPPING_JOBS\" (\"EXTRACTION_ID\",\"CONFIG_ID\",\"ENGINE_VERSION\",\"STATUS\",\"INPUT_SHA256\",\"COMPLETED_AT\") VALUES (?,?,'synthetic-upgrade/1','COMPLETED',?,CURRENT_TIMESTAMP) RETURNING \"ID\"",
                            Long.class,
                            extraction,
                            config,
                            "c".repeat(64));
            long result =
                    jdbc.queryForObject(
                            "INSERT INTO \"CV_MAPPING_RESULTS\" (\"JOB_ID\",\"SCHEMA_VERSION\",\"QUALITY\",\"DIGEST\",\"RESULT\") VALUES (?,1,'NEEDS_REVIEW',?,'{\"synthetic\":true}'::jsonb) RETURNING \"ID\"",
                            Long.class,
                            mapping,
                            "d".repeat(64));
            jdbc.update(
                    "INSERT INTO \"CV_USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-upgrade','synthetic-unused-hash','USER')");
            var sequencesBefore = identitySequences(jdbc);
            assertThat(sequencesBefore).hasSize(15);

            var flyway = Flyway.configure().dataSource(source).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            // OIDs, ownership and identity defaults survive the table moves.
            assertThat(identitySequences(jdbc))
                    .containsExactlyInAnyOrderElementsOf(sequencesBefore);

            var json = new ObjectMapper();
            var detail = new DocumentQueryRepository(jdbc).find(document).orElseThrow();
            assertThat(detail.overview().metadata().id()).isEqualTo(document);
            assertThat(detail.overview().processing().jobId()).isEqualTo(processing);
            assertThat(detail.extractionId()).isEqualTo(extraction);
            assertThat(detail.legacyRawText()).isEqualTo("Synthetic legacy text");
            assertThat(new OriginalPdfRepository(jdbc).metadata(document).orElseThrow().key())
                    .isEqualTo("synthetic-upgrade.pdf");
            var inputs = new StoredOcrExtractionReader(jdbc, json);
            assertThat(inputs.find(extraction).orElseThrow().documentId()).isEqualTo(document);
            var profile = new CandidateProfileRepository(jdbc, json);
            assertThat(profile.exists(candidate)).isTrue();
            assertThat(profile.detail(candidate).getFirst().basic().path("fullName").asString())
                    .isEqualTo("Synthetic upgrade");
            var candidates = new CandidateDocumentRepository(jdbc);
            assertThat(candidates.list(candidate)).hasSize(1);
            assertThat(candidates.list(candidate).getFirst().id()).isEqualTo(document);
            var completed = new CompletedMappingRepository(jdbc).read(result).orElseThrow();
            assertThat(completed.documentId()).isEqualTo(document);
            assertThat(completed.extractionId()).isEqualTo(extraction);
            assertThat(completed.configId()).isEqualTo(config);
            assertThat(json.readTree(completed.body()).path("synthetic").asBoolean()).isTrue();
            var user = new UserRepository(jdbc).find("synthetic-upgrade").orElseThrow();
            assertThat(user.username()).isEqualTo("synthetic-upgrade");
            assertThat(user.role()).isEqualTo("USER");
            assertThat(user.enabled()).isTrue();

            assertThat(candidates.createCandidate()).isGreaterThan(candidate);
            long nextDocument =
                    jdbc.queryForObject(
                            "INSERT INTO \"INGESTION\".\"DOCUMENTS\" (\"ORIGINAL_FILENAME\",\"STORAGE_PATH\",\"FILE_SIZE\",\"MIME_TYPE\",\"SHA256\",\"STATUS\") VALUES ('synthetic-next.pdf','synthetic-next.pdf',1,'application/pdf',?,'PENDING') RETURNING \"ID\"",
                            Long.class,
                            "e".repeat(64));
            assertThat(nextDocument).isGreaterThan(document);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO \"INGESTION\".\"PROCESSING_JOBS\" (\"CV_DOCUMENT_ID\",\"STATUS\") VALUES (?,'PENDING')",
                                            Long.MAX_VALUE))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("FK_JOB_DOCUMENT");
            assertThatThrownBy(() -> candidates.attach(nextDocument, Long.MAX_VALUE))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("FK_DOCUMENT_CANDIDATE");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"MAPPING\".\"CONFIGS\" SET \"DEFINITION\"='{\"changed\":true}'::jsonb WHERE \"ID\"=?",
                                            config))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("Activated mapping configuration is immutable");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"MAPPING\".\"RESULTS\" SET \"RESULT\"='{}'::jsonb WHERE \"ID\"=?",
                                            result))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("Mapping results are immutable");
        }
    }

    @Test
    void latestSchemaHasAllTablesCommentsPrivilegesAndOwnedSequences() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var source = source(database);
            var flyway = Flyway.configure().dataSource(source).load();
            flyway.migrate();
            flyway.validate();
            var jdbc = new JdbcTemplate(source);
            assertThat(TABLES).hasSize(21);
            for (String schema : List.of("INGESTION", "MAPPING", "CANDIDATE", "IDENTITY")) {
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT has_schema_privilege(current_user, ?, 'USAGE')",
                                        Boolean.class,
                                        schema))
                        .as(schema)
                        .isTrue();
            }
            for (Table table : TABLES) {
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind='r'",
                                        Integer.class,
                                        table.schema(),
                                        table.name()))
                        .as(table.qualified())
                        .isEqualTo(1);
                // CANDIDATES keeps its name, but must exist only at its new destination.
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','p') AND c.relname=? AND NOT (n.nspname=? AND c.relname=?)",
                                        Integer.class,
                                        table.oldName(),
                                        table.schema(),
                                        table.name()))
                        .as("legacy table %s", table.oldName())
                        .isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT obj_description(?::regclass, 'pg_class')",
                                        String.class,
                                        table.qualified()))
                        .as("table comment %s", table.qualified())
                        .isNotBlank();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT has_table_privilege(current_user, ?, 'SELECT,INSERT,UPDATE,DELETE')",
                                        Boolean.class,
                                        table.qualified()))
                        .as("table privileges %s", table.qualified())
                        .isTrue();
                assertThat(
                                jdbc.queryForList(
                                        """
                                SELECT a.attname FROM pg_attribute a
                                WHERE a.attrelid=?::regclass AND a.attnum>0 AND NOT a.attisdropped
                                  AND (a.attname ~ '(STATUS|STATE)$'
                                    OR a.attname IN ('ENABLED','DELETED','ONGOING_AS_STATED','QUALITY','END_REASON')
                                    OR EXISTS (SELECT 1 FROM pg_constraint k
                                      WHERE k.conrelid=a.attrelid AND k.contype IN ('p','f')
                                        AND a.attnum=ANY(k.conkey)))
                                  AND NULLIF(btrim(col_description(a.attrelid,a.attnum)),'') IS NULL
                                """,
                                        String.class,
                                        table.qualified()))
                        .as("missing column comments %s", table.qualified())
                        .isEmpty();
            }
            assertThat(
                            jdbc.queryForList(
                                    "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
                                    String.class))
                    .containsExactly("event_publication", "flyway_schema_history");
            assertThat(
                            jdbc.queryForList(
                                    """
                            SELECT s.relname FROM pg_class s
                            JOIN pg_depend d ON d.objid=s.oid AND d.classid='pg_class'::regclass
                              AND d.refclassid='pg_class'::regclass AND d.deptype IN ('a','i')
                            JOIN pg_class t ON t.oid=d.refobjid
                            WHERE s.relkind='S' AND s.relnamespace<>t.relnamespace
                            """,
                                    String.class))
                    .as("owned sequences must move with their tables")
                    .isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    """
                            SELECT count(*) FROM pg_class s
                            WHERE s.relkind='S' AND NOT EXISTS (
                              SELECT 1 FROM pg_depend d WHERE d.objid=s.oid
                                AND d.classid='pg_class'::regclass
                                AND d.refclassid='pg_class'::regclass AND d.deptype IN ('a','i'))
                            """,
                                    Integer.class))
                    .as("no standalone sequences")
                    .isZero();
        }
    }

    private static DriverManagerDataSource source(IsolatedPostgresSchema database)
            throws Exception {
        Map<String, Object> properties = new HashMap<>();
        database.configure((name, supplier) -> properties.put(name, supplier.get()));
        return new DriverManagerDataSource(
                database.jdbcUrl(),
                properties.get("spring.datasource.username").toString(),
                properties.get("spring.datasource.password").toString());
    }

    private static List<Map<String, Object>> identitySequences(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                """
                SELECT t.oid AS table_oid, a.attnum, s.oid AS sequence_oid, s.relname
                FROM pg_class t JOIN pg_attribute a ON a.attrelid=t.oid AND a.attidentity<>''
                JOIN pg_depend d ON d.refobjid=t.oid AND d.refobjsubid=a.attnum
                  AND d.refclassid='pg_class'::regclass AND d.classid='pg_class'::regclass
                  AND d.deptype='i'
                JOIN pg_class s ON s.oid=d.objid AND s.relkind='S'
                ORDER BY t.oid,a.attnum
                """);
    }

    private record Table(String oldName, String schema, String name) {
        String qualified() {
            return "\"" + schema + "\".\"" + name + "\"";
        }
    }
}
