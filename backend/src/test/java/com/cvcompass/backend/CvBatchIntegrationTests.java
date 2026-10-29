package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CvBatchIntegrationTests {
    private TestUserSession auth;

    @BeforeEach
    void authenticateUser() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            auth = TestUserSession.login(client, port, jdbc, json);
        }
    }

    static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static final byte[] PDF = "%PDF-1.7\nsynthetic\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.cvcompass.backend.storage.FileStorageService storage;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void resources(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @BeforeEach
    void assertIsolation() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(DATABASE.schemaName());
        assertThat(DATABASE.storageRoot().getFileName().toString()).startsWith("cv-storage-test-");
    }

    @AfterEach
    void clean() throws Exception {
        jdbc.execute("DELETE FROM \"CV_IMPORT_ITEMS\"");
        jdbc.execute("DELETE FROM \"CV_DOCUMENTS\"");
        jdbc.execute("DELETE FROM \"CV_IMPORT_BATCHES\"");
        try (var files = Files.list(DATABASE.storageRoot())) {
            for (var file : files.toList()) Files.delete(file);
        }
    }

    @AfterAll
    static void shutdown() throws Exception {
        new org.springframework.test.context.TestContextManager(CvBatchIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(
                        org.springframework.test.annotation.DirtiesContext.HierarchyMode
                                .EXHAUSTIVE);
        CLIENT.close();
        DATABASE.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void importsBoundaryCountsWithAtomicCountersAndRelations(int count) throws Exception {
        var response = upload(Collections.nCopies(count, PDF), "files");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var tree = json.readTree(response.body());
        var batch = tree.get("batch");
        assertThat(batch.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(batch.get("totalFiles").asInt()).isEqualTo(count);
        assertThat(batch.get("successCount").asInt()).isEqualTo(count);
        assertThat(batch.get("failedCount").asInt()).isZero();
        assertThat(batch.get("completedAt").isNull()).isFalse();
        assertThat(tree.get("items").size()).isEqualTo(count);
        long id = batch.get("id").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_DOCUMENTS\" WHERE \"BATCH_ID\"=?",
                                Integer.class,
                                id))
                .isEqualTo(count);
        assertThat(get("/api/batches/" + id).statusCode()).isEqualTo(200);
        var page = json.readTree(get("/api/batches/" + id + "/items?size=1&page=0").body());
        assertThat(page.get("totalElements").asInt()).isEqualTo(count);
        assertThat(page.get("content").get(0).get("fileOrder").asInt()).isEqualTo(1);
        assertThat(response.body())
                .doesNotContain("storagePath", "storageKey", DATABASE.storageRoot().toString());
    }

    @Test
    void persistsPartialAndAllFailedResults() throws Exception {
        var partial =
                json.readTree(
                        upload(List.of(PDF, new byte[0], "invalid".getBytes()), "files").body());
        assertThat(partial.get("batch").get("status").asString()).isEqualTo("PARTIAL_FAILED");
        assertThat(partial.get("batch").get("successCount").asInt()).isEqualTo(1);
        assertThat(partial.get("batch").get("failedCount").asInt()).isEqualTo(2);
        var page =
                json.readTree(
                        get("/api/batches/" + partial.get("batch").get("id").asLong() + "/items")
                                .body());
        assertThat(page.get("content").get(1).get("errorCode").asString()).isEqualTo("EMPTY_FILE");
        assertThat(page.get("content").get(2).get("errorCode").asString()).isEqualTo("INVALID_PDF");
        assertThat(page.get("content").get(1).get("documentId").isNull()).isTrue();
        var failed = json.readTree(upload(List.of(new byte[0]), "files").body()).get("batch");
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("completedAt").isNull()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CV_PROCESSING_JOBS\"", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATES\"", Integer.class))
                .isZero();
    }

    @Test
    void rejectsStructureBeforeCreatingBatch() throws Exception {
        assertThat(upload(Collections.nCopies(11, PDF), "files").statusCode()).isEqualTo(400);
        assertThat(upload(List.of(PDF), "file").statusCode()).isEqualTo(400);
        assertThat(upload(List.of(), "files").statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_IMPORT_BATCHES\"", Integer.class))
                .isZero();
    }

    @Test
    void oversizedFileIsIndependentFailure() throws Exception {
        var oversized = new byte[10485761];
        var response = upload(List.of(oversized, PDF), "files");
        assertThat(response.statusCode()).isEqualTo(201);
        var tree = json.readTree(response.body());
        assertThat(tree.get("items").get(0).get("errorCode").asString())
                .isEqualTo("FILE_TOO_LARGE");
        assertThat(tree.get("batch").get("status").asString()).isEqualTo("PARTIAL_FAILED");
    }

    @Test
    void stablePaginationAndValidation() throws Exception {
        long first =
                json.readTree(upload(List.of(PDF), "files").body()).get("batch").get("id").asLong();
        long second =
                json.readTree(upload(List.of(PDF), "files").body()).get("batch").get("id").asLong();
        jdbc.update(
                "UPDATE \"CV_IMPORT_BATCHES\" SET \"CREATED_AT\" = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(
                        json.readTree(get("/api/batches?size=1").body())
                                .get("content")
                                .get(0)
                                .get("id")
                                .asLong())
                .isEqualTo(second);
        assertThat(
                        json.readTree(get("/api/batches?size=1&page=1").body())
                                .get("content")
                                .get(0)
                                .get("id")
                                .asLong())
                .isEqualTo(first);
        for (var path : List.of("/api/batches/99999999", "/api/batches/99999999/items"))
            assertThat(get(path).statusCode()).isEqualTo(404);
        for (var path :
                List.of(
                        "/api/batches/0",
                        "/api/batches?page=-1",
                        "/api/batches?size=101",
                        "/api/batches/1/items?size=0",
                        "/api/batches/nope"))
            assertThat(get(path).statusCode()).as(path).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentOrCounterCommitFailureRollsBackItemAndCompensatesFile(boolean counter)
            throws Exception {
        jdbc.execute(
                "CREATE FUNCTION reject_batch_commit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic commit failure''; END'");
        String table = counter ? "CV_IMPORT_BATCHES" : "CV_DOCUMENTS";
        String event = counter ? "UPDATE" : "INSERT";
        jdbc.execute(
                "CREATE CONSTRAINT TRIGGER reject_batch_commit AFTER "
                        + event
                        + " ON \""
                        + table
                        + "\" DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_batch_commit()");
        try {
            var response = upload(List.of(PDF, PDF), "files");
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body())
                    .doesNotContain(
                            "synthetic commit failure", "SQL", DATABASE.storageRoot().toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_DOCUMENTS\"", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CV_IMPORT_ITEMS\"", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"SUCCESS_COUNT\" FROM \"CV_IMPORT_BATCHES\"",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"STATUS\" FROM \"CV_IMPORT_BATCHES\"", String.class))
                    .isEqualTo("PROCESSING");
            try (var files = Files.list(DATABASE.storageRoot())) {
                assertThat(files.count()).isZero();
            }
        } finally {
            jdbc.execute("DROP TRIGGER reject_batch_commit ON \"" + table + "\"");
            jdbc.execute("DROP FUNCTION reject_batch_commit()");
        }
    }

    @Test
    void failureResultCommitFailureRetainsEarlierSuccessAndStops() throws Exception {
        jdbc.execute(
                "CREATE FUNCTION reject_second_item() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.\"FILE_ORDER\" = 2 THEN RAISE EXCEPTION ''synthetic item failure''; END IF; RETURN NEW; END'");
        jdbc.execute(
                "CREATE CONSTRAINT TRIGGER reject_second_item AFTER INSERT ON \"CV_IMPORT_ITEMS\" DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_second_item()");
        try {
            assertThat(upload(List.of(PDF, new byte[0], PDF), "files").statusCode()).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_DOCUMENTS\"", Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CV_IMPORT_ITEMS\"", Integer.class))
                    .isEqualTo(1);
            var batch = jdbc.queryForMap("SELECT * FROM \"CV_IMPORT_BATCHES\"");
            assertThat(batch.get("SUCCESS_COUNT")).isEqualTo(1);
            assertThat(batch.get("FAILED_COUNT")).isEqualTo(0);
            assertThat(batch.get("TOTAL_FILES")).isEqualTo(3);
            assertThat(batch.get("STATUS")).isEqualTo("PROCESSING");
            assertThat(batch.get("COMPLETED_AT")).isNull();
            try (var files = Files.list(DATABASE.storageRoot())) {
                assertThat(files.count()).isEqualTo(1);
            }
        } finally {
            jdbc.execute("DROP TRIGGER reject_second_item ON \"CV_IMPORT_ITEMS\"");
            jdbc.execute("DROP FUNCTION reject_second_item()");
        }
    }

    @Test
    void tenTenMiBFilesAndFiniteTotalRequestCeiling() throws Exception {
        var accepted = uploadLarge(10);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        assertThat(json.readTree(accepted.body()).get("batch").get("successCount").asInt())
                .isEqualTo(10);
        long before = jdbc.queryForObject("SELECT count(*) FROM \"CV_IMPORT_BATCHES\"", Long.class);
        assertThat(uploadLarge(11).statusCode()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_IMPORT_BATCHES\"", Long.class))
                .isEqualTo(before);
    }

    @Test
    void individualStorageFailureContinuesButUnavailableAborts() throws Exception {
        var failure =
                new com.cvcompass.backend.storage.StorageException(
                        com.cvcompass.backend.storage.StorageException.Reason.WRITE_FAILED,
                        "private detail");
        org.mockito.Mockito.doThrow(failure)
                .doCallRealMethod()
                .when(storage)
                .store(org.mockito.ArgumentMatchers.any());
        var response = upload(List.of(PDF, PDF), "files");
        assertThat(response.statusCode()).isEqualTo(201);
        var tree = json.readTree(response.body());
        assertThat(tree.get("batch").get("status").asString()).isEqualTo("PARTIAL_FAILED");
        assertThat(tree.get("items").get(0).get("errorCode").asString())
                .isEqualTo("FILE_STORAGE_ERROR");
        assertThat(response.body()).doesNotContain("private detail");
        org.mockito.Mockito.doThrow(
                        new com.cvcompass.backend.storage.StorageException(
                                com.cvcompass.backend.storage.StorageException.Reason.UNAVAILABLE,
                                "private unavailable"))
                .when(storage)
                .store(org.mockito.ArgumentMatchers.any());
        assertThat(upload(List.of(PDF, PDF), "files").statusCode()).isEqualTo(500);
    }

    @Test
    void textAndUnexpectedFilePartsRejectWholeRequest() throws Exception {
        for (String extra :
                List.of(
                        "Content-Disposition: form-data; name=\"extra\"\r\n\r\nvalue",
                        "Content-Disposition: form-data; name=\"other\"; filename=\"extra.pdf\"\r\n\r\n%PDF-1.7\n%%EOF")) {
            String body =
                    "--batch-test\r\nContent-Disposition: form-data; name=\"files\"; filename=\"a.pdf\"\r\n\r\n%PDF-1.7\n%%EOF\r\n--batch-test\r\n"
                            + extra
                            + "\r\n--batch-test--\r\n";
            assertThat(send(HttpRequest.BodyPublishers.ofString(body)).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_IMPORT_BATCHES\"", Integer.class))
                .isZero();
    }

    HttpResponse<String> uploadLarge(int count) throws Exception {
        var publishers = new ArrayList<HttpRequest.BodyPublisher>();
        for (int i = 0; i < count; i++) {
            publishers.add(
                    HttpRequest.BodyPublishers.ofString(
                            "--batch-test\r\nContent-Disposition: form-data; name=\"files\"; filename=\"large.pdf\"\r\n\r\n"));
            publishers.add(
                    HttpRequest.BodyPublishers.ofInputStream(
                            () ->
                                    com.cvcompass.backend.ingestion.upload.PdfUploadStreamTests
                                            .pdfOfSize(10485760)));
            publishers.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        publishers.add(HttpRequest.BodyPublishers.ofString("--batch-test--\r\n"));
        return send(
                HttpRequest.BodyPublishers.concat(
                        publishers.toArray(HttpRequest.BodyPublisher[]::new)));
    }

    HttpResponse<String> send(HttpRequest.BodyPublisher body) throws Exception {
        return CLIENT.send(
                auth.request(URI.create("http://localhost:" + port + "/api/batches"))
                        .header("Content-Type", "multipart/form-data; boundary=batch-test")
                        .POST(body)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> upload(List<byte[]> files, String name) throws Exception {
        var body = new ByteArrayOutputStream();
        for (int i = 0; i < files.size(); i++) {
            body.write(
                    ("--batch-test\r\nContent-Disposition: form-data; name=\""
                                    + name
                                    + "\"; filename=\"fixture-"
                                    + i
                                    + ".pdf\"\r\nContent-Type: application/pdf\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            body.write(files.get(i));
            body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.write("--batch-test--\r\n".getBytes(StandardCharsets.US_ASCII));
        return CLIENT.send(
                auth.request(URI.create("http://localhost:" + port + "/api/batches"))
                        .header("Content-Type", "multipart/form-data; boundary=batch-test")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                auth.request(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
