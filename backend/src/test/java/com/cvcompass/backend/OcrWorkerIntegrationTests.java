package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.config.OcrWorkerAuthenticationFilter;
import com.cvcompass.backend.ingestion.service.CvDocumentService;
import com.cvcompass.backend.ingestion.service.CvBatchService;
import com.cvcompass.backend.ingestion.service.DiskImportService;
import com.cvcompass.backend.ingestion.dto.ImportFile;
import com.cvcompass.backend.ingestion.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.nio.file.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL + live HTTP. No fake database or OCR-engine/resource-safety claim. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "cv.ocr.enabled=true","cv.ocr.integration.enabled=true","cv.ocr.integration.reaper-delay=1d",
        "cv.import.enabled=true","cv.import.scan-interval=1d","cv.import.stability-interval=1s"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OcrWorkerIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE=new IsolatedPostgresSchema();
    private static final Path INBOX=inbox();
    private static Path inbox() {
        try { return Files.createTempDirectory("cv-ocr-enqueue-test-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static final String SECRET="s".repeat(43), TOKEN=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final byte[] PDF="%PDF-1.7\nSynthetic fixture\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
        registry.add("cv.import.source-directory",()->INBOX.toString());
        registry.add("cv.ocr.integration.synthetic-storage-root",()->DATABASE.storageRoot().toString());
        registry.add("cv.ocr.integration.namespace",()->"test-only");
        registry.add("cv.ocr.integration.workers.worker.credential-sha256[0]",()->OcrWorkerAuthenticationFilter.hash(SECRET));
        registry.add("cv.ocr.integration.workers.worker.profiles[0]",()->"test-v1");
        registry.add("cv.ocr.integration.profiles.test-v1.worker-commit",()->"a".repeat(40));
        registry.add("cv.ocr.integration.profiles.test-v1.image-id",()->"sha256:"+"b".repeat(64));
        registry.add("cv.ocr.integration.profiles.test-v1.config-fingerprint",()->"c".repeat(64));
        registry.add("cv.ocr.integration.profiles.test-v1.engines-json",OcrWorkerIntegrationTests::engines);
        registry.add("cv.ocr.integration.fixtures[0].fixture-id",()->"known-generated-test");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF));
        registry.add("cv.ocr.integration.fixtures[0].sha256",()->sha);
        registry.add("cv.ocr.integration.fixtures[0].size-bytes",()->PDF.length);
        registry.add("cv.ocr.integration.fixtures[0].profile-revision",()->"test-v1");
    }
    static String engines() {
        var engines=JsonMapper.builder().build().createObjectNode();
        for (String name:Set.of("python","pdfplumber","pdfminer_six","pypdfium2","pdfium","tesseract","leptonica","pillow","worker_version"))
            engines.put(name,"synthetic-test");
        var models=engines.putArray("models");
        for (String name:List.of("vie","eng","osd")) models.addObject().put("name",name).put("revision","a".repeat(40)).put("sha256","b".repeat(64));
        return engines.toString();
    }
    @AfterAll static void cleanup() throws Exception {
        new TestContextManager(OcrWorkerIntegrationTests.class).getTestContext().markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        try (var paths=Files.walk(INBOX)) {
            for (var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        // Each test owns its storage files; close helper requires an empty root.
        if (DATABASE.storageRoot()!=null) try (var entries=java.nio.file.Files.list(DATABASE.storageRoot())) {
            for (var path:entries.toList()) java.nio.file.Files.delete(path);
        }
        DATABASE.close();
    }
    @LocalServerPort int port;
    @Autowired CvDocumentService uploads;
    @Autowired CvBatchService batches;
    @Autowired DiskImportService importer;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name="diskImportClock") Clock diskClock;
    @Autowired CvProcessingJobRepository jobs;
    @Autowired CvExtractionRepository extractions;
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void resetOwnedSchema() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(DATABASE.schemaName());
        for (String table:List.of("CV_DISK_IMPORT_FILES","CV_DISK_OBSERVATIONS","CV_EXTRACTIONS"))
            jdbc.execute("DELETE FROM \""+table+"\"");
        jdbc.execute("UPDATE \"CV_PROCESSING_JOBS\" SET \"CURRENT_ATTEMPT_ID\"=NULL,\"STATUS\"='PENDING'");
        for (String table:List.of("CV_OCR_ATTEMPTS","CV_PROCESSING_JOBS","CV_IMPORT_ITEMS","CV_DOCUMENTS","CV_IMPORT_BATCHES"))
            jdbc.execute("DELETE FROM \""+table+"\"");
        for (Path root:List.of(INBOX,INBOX.resolve("processed"),INBOX.resolve("failed"),DATABASE.storageRoot()))
            try (var paths=Files.list(root)) {
                for (var path:paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().startsWith(".")).toList()) Files.delete(path);
            }
        when(diskClock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
    }
    private HttpResponse<byte[]> send(String method,String path,String body,UUID key,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/ocr/v1"+path))
                .header("Authorization","Bearer "+SECRET).header("X-Lease-Token",token)
                .header("Content-Type","application/json").timeout(java.time.Duration.ofSeconds(10));
        if (key!=null) builder.header("Idempotency-Key",key.toString());
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void metadataClaimDownloadHeartbeatAndExactResultReplay() throws Exception {
        var document=uploads.upload("synthetic.pdf",new ByteArrayInputStream(PDF));
        UUID key=UUID.randomUUID(); String request="{\"protocol\":\"cv-compass.ocr-job/1.0\",\"profile_revision\":\"test-v1\"}";
        var response=send("POST","/claims",request,key,TOKEN); assertThat(response.statusCode()).isEqualTo(201);
        var claim=json.readTree(response.body()).get("data"); String id=claim.get("attempt_id").asString();
        assertThat(claim.at("/ocr_request/document_id").asString()).isEqualTo(document.id().toString());
        assertThat(send("POST","/claims",request,key,TOKEN).statusCode()).isEqualTo(200);
        var input=send("GET","/attempts/"+id+"/input",null,null,TOKEN);
        assertThat(input.statusCode()).isEqualTo(200); assertThat(input.body()).isEqualTo(PDF);
        assertThat(send("PUT","/attempts/"+id+"/lease","{}",null,TOKEN).statusCode()).isEqualTo(200);
        var tree=(ObjectNode)json.readTree(OcrFixtures.envelope("a_"+id.replace("-",""),document.id().toString(),"test-v1"));
        ((ObjectNode)tree.get("ocr_result")).put("config_fingerprint","c".repeat(64));
        ((ObjectNode)tree.get("ocr_result")).set("engines",json.readTree(engines()));
        String body=tree.toString();
        assertThat(send("PUT","/attempts/"+id+"/result",body,null,TOKEN).statusCode()).isEqualTo(201);
        assertThat(send("PUT","/attempts/"+id+"/result",body,null,TOKEN).statusCode()).isEqualTo(200);
        assertThat(send("PUT","/attempts/"+id+"/result",body+" ",null,TOKEN).statusCode()).isEqualTo(409);
        assertThat(send("GET","/attempts/"+id+"/input",null,null,TOKEN).statusCode()).isEqualTo(409);
        assertThat(extractions.count()).isEqualTo(1);
    }
    @Test void unknownSyntheticHashIsStoredWithoutEnrollment() throws Exception {
        long before=jobs.count();
        uploads.upload("not-enrolled.pdf",new ByteArrayInputStream("%PDF-1.7\nOther fixture\n%%EOF\n".getBytes(StandardCharsets.US_ASCII)));
        assertThat(jobs.count()).isEqualTo(before);
    }
    @Test void batchCountersStayIngestionOnlyWhileKnownFixtureEnqueues() {
        batches.upload(List.of(new ImportFile("synthetic.pdf",PDF.length,()->new ByteArrayInputStream(PDF),null)));
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT \"SUCCESS_COUNT\" FROM \"CV_IMPORT_BATCHES\"",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CANDIDATES\"",Integer.class)).isZero();
    }
    @Test void diskHistoryAndJobCommitTogether() throws Exception {
        Files.write(INBOX.resolve("synthetic.pdf"),PDF);
        importer.tick();
        when(diskClock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:02Z"));
        importer.tick();
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_IMPORT_ITEMS\"",Integer.class)).isEqualTo(1);
        assertThat(INBOX.resolve("synthetic.pdf")).doesNotExist();
    }
    @Test void enqueueFailureRollsMetadataBackAndCompensatesRealStorage() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_ocr_enqueue() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic queue failure''; END'");
        jdbc.execute("CREATE TRIGGER reject_ocr_enqueue BEFORE INSERT ON \"CV_PROCESSING_JOBS\" FOR EACH ROW EXECUTE FUNCTION reject_ocr_enqueue()");
        try {
            assertThatThrownBy(()->uploads.upload("synthetic.pdf",new ByteArrayInputStream(PDF))).isInstanceOf(RuntimeException.class);
            assertThat(jobs.count()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_DOCUMENTS\"",Integer.class)).isZero();
            try (var files=Files.list(DATABASE.storageRoot())) { assertThat(files.count()).isZero(); }
        } finally {
            jdbc.execute("DROP TRIGGER reject_ocr_enqueue ON \"CV_PROCESSING_JOBS\""); jdbc.execute("DROP FUNCTION reject_ocr_enqueue()");
        }
    }
}
