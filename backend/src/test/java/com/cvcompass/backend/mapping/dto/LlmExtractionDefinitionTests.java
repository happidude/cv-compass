package com.cvcompass.backend.mapping.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.mapping.service.MappingDigests;
import com.cvcompass.backend.mapping.service.MappingRemoteFixtures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class LlmExtractionDefinitionTests {
    @Test
    void strictV4RoundTripsWithoutEnablingLlmInV3() throws Exception {
        var definition = MappingRemoteFixtures.definition();
        assertThat(
                        MappingVersions.config(
                                MappingDigests.canonical(definition), MappingRemoteFixtures.JSON))
                .isEqualTo(definition);
        assertThat(PersonalInfoDefinition.supportsEngine(LlmExtractionDefinition.ENGINE_VERSION))
                .isFalse();
        try (var stream = getClass().getResourceAsStream("/mapping/personal-info-config-v3.json")) {
            var v3 =
                    (ObjectNode)
                            MappingRemoteFixtures.JSON.readTree(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            v3.put("engineCompatibility", LlmExtractionDefinition.ENGINE_VERSION);
            assertThatThrownBy(
                            () -> MappingVersions.config(v3.toString(), MappingRemoteFixtures.JSON))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMissingExtraAndCoercedNestedKeys() {
        for (String mode : java.util.List.of("missing", "extra", "boolean", "type", "other")) {
            var body =
                    (ObjectNode)
                            MappingRemoteFixtures.JSON.readTree(
                                    MappingDigests.canonical(MappingRemoteFixtures.definition()));
            switch (mode) {
                case "missing" -> body.remove("revision");
                case "extra" -> body.put("extra", 1);
                case "boolean" ->
                        ((ObjectNode) body.path("fields").path("fullName")).put("enabled", "true");
                case "type" ->
                        ((ObjectNode) body.path("fields").path("gender")).put("type", "STRING");
                case "other" -> ((ObjectNode) body.path("other")).put("maxItems", 51);
            }
            assertThatThrownBy(
                            () ->
                                    MappingVersions.config(
                                            body.toString(), MappingRemoteFixtures.JSON))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
