package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.exception.UploadRejectedException;

import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.ingestion.service.CvDocumentService;
import com.cvcompass.backend.ingestion.upload.PdfUploadStreamTests;
import com.cvcompass.backend.storage.FileStorageService;
import com.cvcompass.backend.storage.StorageKey;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CvUploadIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String BOUNDARY = "cv-compass-synthetic-multipart-boundary";
    @LocalServerPort int port;
    @Autowired CvDocumentRepository documents;
    @Autowired FileStorageService storage;
    @Autowired CvDocumentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void isolatedResources(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @BeforeEach
    void assertIsolation() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(DATABASE.schemaName());
        assertThat(DATABASE.storageRoot().getFileName().toString()).startsWith("cv-storage-test-");
    }

    @AfterEach
    void cleanOnlyOwnedResources() throws Exception {
        documents.deleteAll();
        try (var files = Files.list(DATABASE.storageRoot())) {
            for (var file : files.toList()) Files.delete(file);
        }
    }

    @AfterAll
    static void removeIsolatedResources() throws Exception {
        CLIENT.close();
        DATABASE.close();
    }

    @Test
    void uploadPersistsActualMetadataAndCanBeQueriedWithoutPhysicalPaths() throws Exception {
        byte[] pdf = fixturePdf();
        // False client MIME and path-like display name do not control format or physical location.
        var response = upload("C:/client/fixture.pdf", "application/octet-stream", () -> new ByteArrayInputStream(pdf));
        assertThat(response.statusCode()).isEqualTo(201);
        var metadata = json.readTree(response.body());
        long id = metadata.get("id").asLong();
        assertThat(metadata.get("originalFilename").asString()).isEqualTo("fixture.pdf");
        assertThat(metadata.get("sizeBytes").asLong()).isEqualTo(pdf.length);
        assertThat(metadata.get("sha256").asString()).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)));
        assertThat(metadata.get("mimeType").asString()).isEqualTo("application/pdf");
        assertThat(metadata.get("status").asString()).isEqualTo("PENDING");
        assertThat(metadata.get("createdAt").asString()).isNotBlank();
        assertThat(response.headers().firstValue("Location")).contains("/api/documents/" + id);
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("upload-integration");
        assertThat(response.body()).doesNotContain("storagePath", "storageKey", DATABASE.storageRoot().toString());

        var entity = documents.findById(id).orElseThrow();
        assertThat(entity.getBatch()).isNull();
        assertThat(entity.getCandidate()).isNull();
        assertThat(entity.getStoragePath()).matches("[a-f0-9]{32}");
        try (var content = storage.read(new StorageKey(entity.getStoragePath()))) {
            assertThat(content.readAllBytes()).isEqualTo(pdf);
        }
        assertThat(count("CV_IMPORT_BATCHES")).isZero();
        assertThat(count("CV_PROCESSING_JOBS")).isZero();
        assertThat(count("CANDIDATES")).isZero();

        var queried = get("/api/documents/" + id);
        assertThat(queried.statusCode()).isEqualTo(200);
        assertThat(json.readTree(queried.body())).isEqualTo(metadata);
    }

    @Test
    void acceptsAnActualTenMiBFile() throws Exception {
        var response = upload("limit.pdf", "application/pdf", () -> PdfUploadStreamTests.pdfOfSize(10L * 1024 * 1024));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json.readTree(response.body()).get("sizeBytes").asLong()).isEqualTo(10L * 1024 * 1024);
        assertThat(documents.count()).isEqualTo(1);
    }

    @Test
    void rejectsOneByteAboveLimitAtTheServletBoundary() throws Exception {
        var response = upload("oversize.pdf", "application/pdf",
                () -> PdfUploadStreamTests.pdfOfSize(10L * 1024 * 1024 + 1));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertNoFilesOrMetadata();
    }

    @Test
    void serviceSizeGuardAlsoCleansPartialStorageIndependentlyOfServlet() {
        assertThatThrownBy(() -> service.upload("oversize.pdf",
                PdfUploadStreamTests.pdfOfSize(10L * 1024 * 1024 + 1)))
                .isInstanceOf(UploadRejectedException.class);
        assertNoFilesOrMetadata();
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        var response = upload("empty.pdf", "application/pdf", InputStream::nullInputStream);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("EMPTY_FILE");
        assertNoFilesOrMetadata();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake PDF content", "%PDF-1.7\nno eof", "%PDF-1.7\n%%EOF\nuntrusted suffix"})
    void rejectsInvalidContentEvenWhenFilenameAndMimeClaimPdf(String content) throws Exception {
        var response = upload("claimed.pdf", "application/pdf",
                () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII)));
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_PDF");
        assertNoFilesOrMetadata();
    }

    @Test
    void duplicateFilePartsAreRejected() throws Exception {
        var body = HttpRequest.BodyPublishers.concat(part("file", "one.pdf", "application/pdf", () -> new ByteArrayInputStream(fixturePdf())),
                part("file", "two.pdf", "application/pdf", () -> new ByteArrayInputStream(fixturePdf())), ending());
        var response = send(body);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_MULTIPART");
        assertNoFilesOrMetadata();
    }

    @Test
    void unexpectedFilePartIsRejected() throws Exception {
        var response = send(HttpRequest.BodyPublishers.concat(part("other", "fixture.pdf", "application/pdf",
                () -> new ByteArrayInputStream(fixturePdf())), ending()));
        assertThat(response.statusCode()).isEqualTo(400);
        assertNoFilesOrMetadata();
    }

    @Test
    void extraTextPartIsRejected() throws Exception {
        var response = send(HttpRequest.BodyPublishers.concat(part("file", "fixture.pdf", "application/pdf",
                        () -> new ByteArrayInputStream(fixturePdf())),
                HttpRequest.BodyPublishers.ofString("--" + BOUNDARY
                        + "\r\nContent-Disposition: form-data; name=\"checksum\"\r\n\r\nfake\r\n"), ending()));
        assertThat(response.statusCode()).isEqualTo(400);
        assertNoFilesOrMetadata();
    }

    @Test
    void missingDocumentReturnsStandard404() throws Exception {
        var response = get("/api/documents/9223372036854775807");
        assertThat(response.statusCode()).isEqualTo(404);
        var error = json.readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.get("correlationId").asString()).isEqualTo("query-integration");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number"})
    void invalidDocumentIdsAreRejected(String id) throws Exception {
        assertThat(get("/api/documents/" + id).statusCode()).isEqualTo(400);
    }

    @Test
    void genuineDeferredDatabaseCommitFailureCompensatesPublishedFile() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_upload_commit() RETURNS trigger LANGUAGE plpgsql AS "
                + "'BEGIN RAISE EXCEPTION ''synthetic deferred commit failure''; END'");
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_upload_commit AFTER INSERT ON \"CV_DOCUMENTS\" "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_upload_commit()");
        try {
            var response = upload("fixture.pdf", "application/pdf", () -> new ByteArrayInputStream(fixturePdf()));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
            assertThat(response.body()).doesNotContain("synthetic deferred commit failure", DATABASE.storageRoot().toString());
            assertNoFilesOrMetadata();
        } finally {
            jdbc.execute("DROP TRIGGER reject_upload_commit ON \"CV_DOCUMENTS\"");
            jdbc.execute("DROP FUNCTION reject_upload_commit()");
        }
    }

    @Test
    void migrationV3AllowsNullBatchAndKeepsForeignKey() {
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = 'CV_DOCUMENTS' AND column_name = 'BATCH_ID'",
                String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = current_schema() AND constraint_name = 'FK_DOCUMENT_BATCH'", Long.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"multipart/form-data", "multipart/form-data; boundary=missing"})
    void malformedMultipartIsAClientErrorWithoutEchoingParserDetails(String contentType) throws Exception {
        var response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/documents"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
                .header("X-Correlation-ID", "malformed-multipart")
                .POST(HttpRequest.BodyPublishers.ofString("synthetic-untrusted-multipart-body")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        var error = json.readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo("INVALID_MULTIPART");
        assertThat(error.get("correlationId").asString()).isEqualTo("malformed-multipart");
        assertThat(response.body()).doesNotContain("synthetic-untrusted-multipart-body", "Exception", "boundary");
        assertNoFilesOrMetadata();
    }
    private void assertNoFilesOrMetadata() {
        assertThat(documents.count()).isZero();
        try (var files = Files.list(DATABASE.storageRoot())) {
            assertThat(files.toList()).isEmpty();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\"", Long.class);
    }

    private HttpResponse<String> upload(String filename, String mime, Supplier<InputStream> content) throws Exception {
        return send(HttpRequest.BodyPublishers.concat(part("file", filename, mime, content), ending()));
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/documents"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Correlation-ID", "upload-integration").POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("X-Correlation-ID", "query-integration").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher part(String field, String filename, String mime, Supplier<InputStream> content) {
        return HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString("--" + BOUNDARY
                        + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename
                        + "\"\r\nContent-Type: " + mime + "\r\n\r\n"),
                HttpRequest.BodyPublishers.ofInputStream(content), HttpRequest.BodyPublishers.ofString("\r\n"));
    }

    private static HttpRequest.BodyPublisher ending() {
        return HttpRequest.BodyPublishers.ofString("--" + BOUNDARY + "--\r\n");
    }
    private static byte[] fixturePdf() {
        var output = new ByteArrayOutputStream();
        try {
            output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            var offsets = new java.util.ArrayList<Integer>();
            var objects = List.of("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> >>");
            for (int index = 0; index < objects.size(); index++) {
                offsets.add(output.size());
                output.write(((index + 1) + " 0 obj\n" + objects.get(index) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
            }
            int xref = output.size();
            output.write("xref\n0 4\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
            for (int offset : offsets) {
                output.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
            }
            output.write(("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
