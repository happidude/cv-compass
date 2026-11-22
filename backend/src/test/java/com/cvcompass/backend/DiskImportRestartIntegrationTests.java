package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;

import com.cvcompass.backend.ingestion.service.DiskImportService;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;

/** Full Spring context restart; the second runner has no state from the first JVM-level service. */
class DiskImportRestartIntegrationTests {
    @Test
    void committedHistoryAndSourceOwnershipSurviveApplicationRestart() throws Exception {
        var database = new IsolatedPostgresSchema();
        var settings = new HashMap<String, Object>();
        database.configure((key, value) -> settings.put(key, value.get()));
        Path inbox = Files.createTempDirectory("cv-import-restart-");
        try {
            Files.writeString(inbox.resolve("a.pdf"), "%PDF-1.7\nfirst\n%%EOF\n");
            try (var first = start(settings, inbox, database.storageRoot())) {
                runStable(first);
                assertThat(documents(settings)).isEqualTo(1);
            }
            Files.writeString(inbox.resolve("a.pdf"), "%PDF-1.7\nfirst\n%%EOF\n");
            try (var second = start(settings, inbox, database.storageRoot())) {
                runStable(second);
                assertThat(documents(settings)).isEqualTo(1);
                Files.writeString(inbox.resolve("a.pdf"), "%PDF-1.7\nsecond content\n%%EOF\n");
                runStable(second);
                assertThat(documents(settings)).isEqualTo(2);
            }
        } finally {
            try (var paths = Files.walk(inbox)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
            try (var paths = Files.list(database.storageRoot())) {
                for (var path : paths.toList()) Files.delete(path);
            }
            database.close();
        }
    }

    private ConfigurableApplicationContext start(
            Map<String, Object> settings, Path inbox, Path storage) {
        var app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(settings);
        return app.run(
                "--spring.profiles.active=verification",
                "--spring.config.import=",
                "--cv.storage.provider=local",
                "--cv.storage.local.root-directory=" + storage,
                "--cv.import.enabled=true",
                "--cv.import.provider=local",
                "--cv.import.source-directory=" + inbox,
                "--cv.import.scan-interval=1d",
                "--cv.import.stability-interval=1ms");
    }

    private void runStable(ConfigurableApplicationContext context) throws Exception {
        context.getBean(DiskImportService.class).tick();
        Thread.sleep(20);
        context.getBean(DiskImportService.class).tick();
    }

    private long documents(Map<String, Object> settings) throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                settings.get("spring.datasource.url").toString(),
                                settings.get("spring.datasource.username").toString(),
                                settings.get("spring.datasource.password").toString());
                var statement = connection.createStatement()) {
            try (var result =
                    statement.executeQuery("SELECT count(*) FROM \"INGESTION\".\"DOCUMENTS\"")) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
