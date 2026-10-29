package com.cvcompass.backend;

import com.cvcompass.backend.candidate.service.PublicationService;
import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.service.OcrJobService;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.storage.FileStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

/** Explicit opt-in synthetic smoke only; never runs as application/test startup. */
public final class M11SmokeServer {
    private M11SmokeServer() {}

    public static void main(String[] arguments) throws Exception {
        if (!"1".equals(System.getenv("M11_RUN_SMOKE")))
            throw new IllegalStateException("M11_SMOKE_OPT_IN_REQUIRED");
        Path output = Path.of("build/m11/live").toAbsolutePath();
        Files.createDirectories(output);
        for (String marker : java.util.List.of("ready.json", "stop", "remap", "remap-done"))
            Files.deleteIfExists(output.resolve(marker));
        try (var database = new IsolatedPostgresSchema()) {
            Map<String, Object> properties = new HashMap<>();
            database.configure((name, supplier) -> properties.put(name, supplier.get()));
            try (var context =
                    new SpringApplicationBuilder(BackendApplication.class)
                            .initializers(
                                    application ->
                                            application
                                                    .getEnvironment()
                                                    .getPropertySources()
                                                    .addFirst(
                                                            new org.springframework.core.env
                                                                    .MapPropertySource(
                                                                    "m11-isolated", properties)))
                            .run(
                                    "--spring.profiles.active=test",
                                    "--server.port=0",
                                    "--server.address=127.0.0.1",
                                    "--server.servlet.session.cookie.secure=false",
                                    "--cv.ocr.enabled=true",
                                    "--cv.mapping.enabled=false",
                                    "--cv.candidate.publication.enabled=false")) {
                var fixture = new M11IntegrationTests();
                fixture.jdbc = context.getBean(JdbcTemplate.class);
                fixture.json = context.getBean(ObjectMapper.class);
                fixture.storage = context.getBean(FileStorageService.class);
                fixture.mapping = context.getBean(MappingService.class);
                fixture.ocr = context.getBean(OcrJobService.class);
                fixture.documents = context.getBean(CvDocumentRepository.class);
                fixture.jobs = context.getBean(CvProcessingJobRepository.class);
                var publication = context.getBean(PublicationService.class);
                var doc = fixture.document();
                publication.publish(fixture.result(doc, fixture.raw(), false));
                fixture.jdbc.update(
                        "INSERT INTO \"CV_USERS\"(\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-smoke',?,'USER')",
                        new BCryptPasswordEncoder(4).encode("synthetic-smoke-only"));
                long candidate =
                        fixture.jdbc.queryForObject(
                                "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                                Long.class,
                                doc.getId());
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                Files.writeString(
                        output.resolve("ready.json"),
                        fixture.json.writeValueAsString(
                                Map.of(
                                        "port",
                                        port,
                                        "candidateId",
                                        candidate,
                                        "documentId",
                                        doc.getId())));
                Instant deadline = Instant.now().plusSeconds(300);
                while (Instant.now().isBefore(deadline) && !Files.exists(output.resolve("stop"))) {
                    if (Files.exists(output.resolve("remap"))
                            && !Files.exists(output.resolve("remap-done"))) {
                        publication.publish(
                                fixture.result(doc, fixture.raw().replace("2018", "2019"), false));
                        Files.writeString(output.resolve("remap-done"), "done");
                    }
                    Thread.sleep(100);
                }
                for (var key : M11IntegrationTests.KEYS) fixture.storage.delete(key);
            }
        }
    }
}
