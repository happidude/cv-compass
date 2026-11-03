package com.cvcompass.backend.mapping.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.mapping.config.MappingBootstrap;
import com.cvcompass.backend.mapping.service.MappingDigests;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MappingVersionsTests {
    private final JsonMapper json = JsonMapper.builder().build();

    private String sample() throws Exception {
        try (var input = getClass().getResourceAsStream("/mapping/personal-info-config-v3.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsCompleteSixFieldConfigurationAndKeepsDisabledGender() throws Exception {
        var config = (PersonalInfoDefinition) MappingVersions.config(sample(), json);
        config.validate();
        assertThat(config.schemaVersion()).isEqualTo(3);
        assertThat(config.personalFields()).hasSize(6);
        assertThat(config.personalFields().get("gender").labels())
                .containsExactly("Gender", "Sex", "Giới tính", "Phái");
        var node = (ObjectNode) json.readTree(sample());
        ((ObjectNode) node.path("personalFields").path("gender")).put("enabled", false);
        var disabled =
                (PersonalInfoDefinition)
                        MappingVersions.config(json.writeValueAsString(node), json);
        disabled.validate();
        assertThat(disabled.personalFields().get("gender").enabled()).isFalse();
        assertThat(disabled.experience().schemaVersion()).isEqualTo(2);
    }

    @Test
    void rejectsUnknownKeysCoercionsMissingFieldsAndUnsafePriority() throws Exception {
        for (String replacement :
                new String[] {
                    sample().replace("\"enabled\": true", "\"enabled\": \"true\""),
                    sample().replace(
                                    "\"revision\": \"personal-info-synthetic-v3\"",
                                    "\"revision\": \"\""),
                    sample().replace("\"EXPLICIT_LABEL\"", "\"RUN_SCRIPT\""),
                    sample().replace(
                                    "\"schemaVersion\": 3",
                                    "\"schemaVersion\": 3, \"regex\": \".*\""),
                    sample().replace("\"Gender\"", "\"Mr\""),
                    sample().replace("\"Gender\"", "\"\"")
                }) {
            assertThatThrownBy(() -> MappingVersions.config(replacement, json))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var node = (ObjectNode) json.readTree(sample());
        ((ObjectNode) node.path("personalFields")).remove("gender");
        assertThatThrownBy(() -> MappingVersions.config(json.writeValueAsString(node), json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyV1ConfigurationAndResultKeepTheirCanonicalDigest() {
        String v1 =
                "{\"schemaVersion\":1,\"fields\":[{\"key\":\"sample\",\"type\":\"STRING\",\"scope\":\"PAGE\",\"extractor\":\"FIRST_LINE\",\"argument\":null}],\"engineCompatibility\":\"literal-mapping/1.0\"}";
        var legacy = MappingVersions.config(v1, json);
        assertThat(legacy).isInstanceOf(MappingDefinition.class);
        assertThat(MappingDigests.canonical(legacy))
                .isEqualTo(MappingDigests.canonical(json.readValue(v1, MappingDefinition.class)));
    }

    @Test
    void v3CanonicalConfigurationIgnoresMapInsertionOrderAndKeepsLegacyV2Reader() throws Exception {
        var original = (PersonalInfoDefinition) MappingVersions.config(sample(), json);
        var node = (ObjectNode) json.readTree(sample());
        for (String key : java.util.List.of("personalFields", "sectionHeadings")) {
            var source = node.path(key);
            var names = new java.util.ArrayList<>(source.propertyNames());
            var reversed = node.putObject(key);
            for (String name : names.reversed()) reversed.set(name, source.get(name));
        }
        var reordered = MappingVersions.config(json.writeValueAsString(node), json);
        assertThat(MappingDigests.canonical(reordered))
                .isEqualTo(MappingDigests.canonical(original));
        assertThat(new java.util.ArrayList<>(original.personalFields().keySet())).isSorted();
        assertThat(new java.util.ArrayList<>(original.sectionHeadings().keySet())).isSorted();
        String legacyBody = json.writeValueAsString(node.path("experience"));
        var legacy = MappingVersions.config(legacyBody, json);
        assertThat(legacy).isInstanceOf(ExperienceDefinition.class);
        assertThat(MappingDigests.canonical(legacy))
                .isEqualTo(MappingDigests.canonical(original.experience()));
        for (String field : PersonalInfoDefinition.FIELD_NAMES) {
            var disabledNode = (ObjectNode) json.readTree(sample());
            ((ObjectNode) disabledNode.path("personalFields").path(field)).put("enabled", false);
            var disabled =
                    (PersonalInfoDefinition)
                            MappingVersions.config(json.writeValueAsString(disabledNode), json);
            disabled.validate();
            assertThat(disabled.personalFields().get(field).enabled()).isFalse();
        }
    }

    @Test
    void missingDefinitionAndRevisionHaveFixedDiagnostics() {
        assertThatThrownBy(() -> new MappingBootstrap(null, json, "", "", true).run(null))
                .hasMessage("MAPPING_DEFINITION_REQUIRED");
        assertThatThrownBy(
                        () ->
                                new MappingBootstrap(null, json, "synthetic-file", "", true)
                                        .run(null))
                .hasMessage("MAPPING_REVISION_REQUIRED");
        new MappingBootstrap(null, json, "", "", false).run(null);
    }

    @Test
    void readsV3GenderAndReusesLegacyResultReadersWithoutRewriting() {
        var fields = new java.util.HashMap<String, PersonalInfoMappingResult.PersonalField>();
        for (String name : PersonalInfoDefinition.FIELD_NAMES)
            fields.put(
                    name,
                    new PersonalInfoMappingResult.PersonalField(
                            true,
                            null,
                            null,
                            "NOT_FOUND",
                            java.util.List.of(),
                            java.util.List.of(),
                            java.util.List.of()));
        fields.put(
                "gender",
                new PersonalInfoMappingResult.PersonalField(
                        true,
                        "Male",
                        "MALE",
                        "PROPOSED",
                        java.util.List.of(),
                        java.util.List.of(new MappingResult.Evidence(1, 1, 0, 4, "Male")),
                        java.util.List.of()));
        var v3 =
                new PersonalInfoMappingResult(
                        3,
                        1,
                        "cv-compass.ocr/2.0",
                        2,
                        "synthetic-v3",
                        PersonalInfoDefinition.ENGINE_VERSION,
                        "NEEDS_REVIEW",
                        false,
                        null,
                        java.util.List.of(),
                        "a".repeat(64),
                        3,
                        "synthetic-profile",
                        "rules-v1",
                        "taxonomy-v1",
                        java.util.List.of(),
                        java.util.List.of(),
                        fields);
        String body = MappingDigests.canonical(v3);
        assertThat(MappingVersions.result(body, json)).isEqualTo(v3);
        assertThat(body).contains("MALE");
        fields.put(
                "gender",
                new PersonalInfoMappingResult.PersonalField(
                        true,
                        "Other",
                        null,
                        "INVALID",
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(
                                new ExperienceMappingResult.Issue(
                                        "GENDER_UNRECOGNIZED", "gender", java.util.List.of()))));
        var unknown =
                new PersonalInfoMappingResult(
                        3,
                        1,
                        "cv-compass.ocr/2.0",
                        2,
                        "synthetic-v3",
                        PersonalInfoDefinition.ENGINE_VERSION,
                        "NEEDS_REVIEW",
                        false,
                        null,
                        java.util.List.of(),
                        "a".repeat(64),
                        3,
                        "synthetic-profile",
                        "rules-v1",
                        "taxonomy-v1",
                        java.util.List.of(),
                        java.util.List.of(),
                        fields);
        assertThat(
                        ((PersonalInfoMappingResult)
                                        MappingVersions.result(
                                                MappingDigests.canonical(unknown), json))
                                .personalInfo()
                                .get("gender")
                                .raw())
                .isEqualTo("Other");
        assertThatThrownBy(() -> MappingVersions.result(body.replace("MALE", "UNKNOWN"), json))
                .isInstanceOf(RuntimeException.class);
        var v2 =
                new ExperienceMappingResult(
                        2,
                        1,
                        "cv-compass.ocr/2.0",
                        2,
                        "legacy-v2",
                        ExperienceDefinition.ENGINE_VERSION,
                        "NEEDS_REVIEW",
                        false,
                        null,
                        java.util.List.of(),
                        "b".repeat(64),
                        3,
                        "legacy-profile",
                        "rules-v1",
                        "taxonomy-v1",
                        java.util.List.of(),
                        java.util.List.of());
        var v1 =
                new MappingResult(
                        1,
                        1,
                        "cv-compass.ocr/2.0",
                        2,
                        "legacy-v1",
                        MappingDefinition.ENGINE_VERSION,
                        "NEEDS_REVIEW",
                        false,
                        null,
                        java.util.List.of());
        for (MappingOutcome legacy : java.util.List.of(v1, v2)) {
            String before = MappingDigests.canonical(legacy);
            assertThat(MappingVersions.result(before, json)).isEqualTo(legacy);
            assertThat(MappingDigests.canonical(MappingVersions.result(before, json)))
                    .isEqualTo(before);
        }
    }
}
