package com.cvcompass.backend.mapping.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.mapping.dto.MappingDefinition;
import com.cvcompass.backend.mapping.service.MappingService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

class MappingBootstrapTests {
    @TempDir Path directory;
    private final MappingService mapping = mock(MappingService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void externalDefinitionRegistersAndActivatesSelectedRevision() throws Exception {
        Path file = directory.resolve("definition.json");
        byte[] fixture;
        try (var stream = getClass().getResourceAsStream("/mapping/synthetic-definition.json")) {
            fixture = stream.readAllBytes();
        }
        Files.write(file, fixture);
        var expected = json.readValue(fixture, MappingDefinition.class);
        expected.validate();
        when(mapping.register("synthetic-v1", expected)).thenReturn(17L);
        new MappingBootstrap(mapping, json, file.toString(), "synthetic-v1")
                .run(new DefaultApplicationArguments());
        verify(mapping).register("synthetic-v1", expected);
        verify(mapping).activate(17L);
    }

    @Test
    void malformedAndOversizedDefinitionsNeverActivate() throws Exception {
        Path file = directory.resolve("invalid.json");
        for (String body : new String[] {"{malformed", " ".repeat(65537)}) {
            Files.writeString(file, body);
            assertThatThrownBy(
                            () ->
                                    new MappingBootstrap(
                                                    mapping, json, file.toString(), "synthetic-v1")
                                            .run(new DefaultApplicationArguments()))
                    .hasMessage("Mapping configuration could not be activated")
                    .hasNoCause();
        }
        verifyNoInteractions(mapping);
    }

    @Test
    void registrationErrorDoesNotExposeConfigurationOrSqlDetails() throws Exception {
        Path file = directory.resolve("definition.json");
        Files.writeString(
                file,
                """
                {"schemaVersion":1,"engineCompatibility":"literal-mapping/1.0","fields":[{"key":"sample","type":"STRING","scope":"PAGE","extractor":"FIRST_LINE","argument":null}]}
                """);
        when(mapping.register(eq("synthetic-v1"), any()))
                .thenThrow(new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC"));
        try {
            new MappingBootstrap(mapping, json, file.toString(), "synthetic-v1")
                    .run(new DefaultApplicationArguments());
            throw new AssertionError("Expected bootstrap failure");
        } catch (IllegalStateException failure) {
            assertThat(failure)
                    .hasMessage("Mapping configuration could not be activated")
                    .hasNoCause();
            assertThat(failure.toString()).doesNotContain("SYNTHETIC_PRIVATE_DIAGNOSTIC");
            verify(mapping).register(eq("synthetic-v1"), any());
        }
    }
}
