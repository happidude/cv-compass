package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.mapping.dto.MappingDefinition;
import com.cvcompass.backend.mapping.service.MappingService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Trusted operator file only; no public administration endpoint is introduced in this milestone. */
@Component
@ConditionalOnProperty(name = "cv.mapping.definition-file")
public class MappingBootstrap implements ApplicationRunner {
    private final MappingService mapping;
    private final ObjectMapper json;
    private final Path file;
    private final String revision;

    public MappingBootstrap(
            MappingService mapping,
            ObjectMapper json,
            @Value("${cv.mapping.definition-file}") String file,
            @Value("${cv.mapping.revision}") String revision) {
        this.mapping = mapping;
        this.json = json;
        this.file = Path.of(file);
        this.revision = revision;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            byte[] bytes;
            try (var stream = Files.newInputStream(file)) {
                bytes = stream.readNBytes(65537);
            }
            if (bytes.length > 65536)
                throw new IllegalArgumentException("Mapping config too large");
            var definition = json.readValue(bytes, MappingDefinition.class);
            mapping.activate(mapping.register(revision, definition));
        } catch (Exception invalid) {
            // JSON/SQL errors may contain source text; expose only a fixed startup failure.
            throw new IllegalStateException("Mapping configuration could not be activated");
        }
    }
}
