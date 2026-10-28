package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP coverage of non-default batch limits without reducing the M4 limit. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cv.batch.max-files=2", "cv.batch.max-file-bytes=100"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CvBatchConfiguredLimitsIntegrationTests {
    private TestUserSession auth;

    @BeforeEach
    void authenticateUser() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            auth = TestUserSession.login(client, port, jdbc, json);
        }
    }

    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void resources(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void shutdown() throws Exception {
        new org.springframework.test.context.TestContextManager(
                        CvBatchConfiguredLimitsIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(
                        org.springframework.test.annotation.DirtiesContext.HierarchyMode
                                .EXHAUSTIVE);
        try (var files = Files.list(DATABASE.storageRoot())) {
            for (var file : files.toList()) Files.delete(file);
        }
        DATABASE.close();
    }

    @Test
    void configuredCountAndByteBoundariesPreserveStandaloneUploads() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(DATABASE.schemaName());
        try (var client = HttpClient.newHttpClient()) {
            var rejected = upload(client, "/api/batches", "files", 3, 100);
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CV_IMPORT_BATCHES\"", Integer.class))
                    .isZero();
            var accepted = upload(client, "/api/batches", "files", 2, 100);
            assertThat(accepted.statusCode()).isEqualTo(201);
            assertThat(json.readTree(accepted.body()).get("batch").get("successCount").asInt())
                    .isEqualTo(2);
            var oversized = upload(client, "/api/batches", "files", 1, 101);
            assertThat(oversized.statusCode()).isEqualTo(201);
            var result = json.readTree(oversized.body());
            assertThat(result.get("batch").get("status").asString()).isEqualTo("FAILED");
            assertThat(result.get("batch").get("failedCount").asInt()).isEqualTo(1);
            assertThat(result.get("batch").get("completedAt").isNull()).isFalse();
            assertThat(result.get("items").get(0).get("errorCode").asString())
                    .isEqualTo("FILE_TOO_LARGE");
            var standalone = upload(client, "/api/documents", "file", 1, 101);
            assertThat(standalone.statusCode()).isEqualTo(201);
            assertThat(json.readTree(standalone.body()).get("sizeBytes").asInt()).isEqualTo(101);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CV_IMPORT_BATCHES\"", Integer.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CV_DOCUMENTS\" WHERE \"BATCH_ID\" IS NULL",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    private HttpResponse<String> upload(
            HttpClient client, String path, String field, int count, int size) throws Exception {
        var body = new StringBuilder();
        String pdf = "%PDF-1.7\n" + " ".repeat(size - 16) + "\n%%EOF\n";
        for (int index = 0; index < count; index++) {
            body.append("--limits\r\nContent-Disposition: form-data; name=\"")
                    .append(field)
                    .append("\"; filename=\"fixture.pdf\"\r\n\r\n")
                    .append(pdf)
                    .append("\r\n");
        }
        body.append("--limits--\r\n");
        return client.send(
                auth.request(URI.create("http://localhost:" + port + path))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .header("Content-Type", "multipart/form-data; boundary=limits")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
