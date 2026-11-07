package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.web.OperationalLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Trusted operator file only; no public administration endpoint is introduced in this milestone. */
@Component
public class MappingBootstrap implements ApplicationRunner {
    private final MappingService mapping;
    private final ObjectMapper json;
    private final String file;
    private final String revision;
    private final boolean enabled;

    @Autowired
    public MappingBootstrap(
            MappingService mapping,
            ObjectMapper json,
            @Value("${cv.mapping.definition-file:}") String file,
            @Value("${cv.mapping.revision:}") String revision,
            @Value("${cv.mapping.enabled:false}") boolean enabled) {
        this.mapping = mapping;
        this.json = json;
        this.file = file;
        this.revision = revision;
        this.enabled = enabled;
    }

    public MappingBootstrap(
            MappingService mapping, ObjectMapper json, String file, String revision) {
        this(mapping, json, file, revision, true);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled && (file == null || file.isBlank())) return;
        if (file == null || file.isBlank()) throw failure("MAPPING_DEFINITION_REQUIRED");
        if (revision == null || revision.isBlank()) throw failure("MAPPING_REVISION_REQUIRED");
        try {
            byte[] bytes;
            try (var stream = Files.newInputStream(Path.of(file))) {
                bytes = stream.readNBytes(65537);
            }
            if (bytes.length > 65536) throw new IllegalArgumentException();
            var definition =
                    MappingVersions.config(new String(bytes, StandardCharsets.UTF_8), json);
            definition.validate();
            mapping.activate(mapping.register(revision, definition));
        } catch (IllegalStateException rejected) {
            if ("MAPPING_V3_ACTIVATION_NOT_AVAILABLE".equals(rejected.getMessage())) throw rejected;
            throw failure("MAPPING_CONFIG_INVALID");
        } catch (Exception invalid) {
            // JSON/SQL errors may contain source text; never expose their cause or message.
            throw failure("MAPPING_CONFIG_INVALID");
        }
    }

    private static IllegalStateException failure(String code) {
        OperationalLog.write(
                Level.WARN, OperationalLog.Event.MAPPING_SCHEDULER_FAILED, code, Map.of());
        return new IllegalStateException(
                "MAPPING_CONFIG_INVALID".equals(code)
                        ? "Mapping configuration could not be activated"
                        : code);
    }
}
