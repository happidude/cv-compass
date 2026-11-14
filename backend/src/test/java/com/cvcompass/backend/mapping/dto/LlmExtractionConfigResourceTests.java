package com.cvcompass.backend.mapping.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LlmExtractionConfigResourceTests {
    private final JsonMapper json = JsonMapper.builder().build();

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/mapping/" + name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void loadsAndValidatesCompleteLlmResource() throws Exception {
        var config =
                (LlmExtractionDefinition)
                        MappingVersions.config(resource("llm-extraction-config-v1.json"), json);
        config.validate();

        assertThat(config.schemaVersion()).isEqualTo(4);
        assertThat(config.engineCompatibility()).isEqualTo("llm-extraction/1.0");
        assertThat(config.revision()).isEqualTo("llm-extraction-v1-r1");
        var types =
                Map.of(
                        "fullName", "STRING",
                        "email", "STRING",
                        "phone", "STRING",
                        "location", "STRING",
                        "summary", "STRING",
                        "gender", "ENUM_GENDER",
                        "dateOfBirth", "DATE_PARTIAL",
                        "experiences", "EXPERIENCE_LIST");
        assertThat(config.fieldDefinitions()).containsOnlyKeys(types.keySet());
        types.forEach(
                (name, type) -> {
                    var field = config.fieldDefinitions().get(name);
                    assertThat(field.enabled()).isTrue();
                    assertThat(field.type()).isEqualTo(type);
                    assertThat(field.description()).contains("verbatim", "CV", "do not");
                    assertThat(field.aliases()).isNotEmpty();
                });
        assertThat(config.fieldDefinitions().get("fullName").aliases())
                .contains("Họ và tên", "Full name");
        assertThat(config.fieldDefinitions().get("dateOfBirth").aliases())
                .contains("Ngày sinh", "Date of birth");
        assertThat(config.fieldDefinitions().get("experiences").aliases())
                .contains("Kinh nghiệm làm việc", "Work experience");
        assertThat(config.other().enabled()).isTrue();
        assertThat(config.other().maxItems()).isEqualTo(20);
        assertThat(config.other().description()).contains("verbatim", "evidence", "do not infer");
    }

    @Test
    void rejectsLlmResourceAsV3AndLlmEngineOnValidV3Shape() throws Exception {
        var llm = (ObjectNode) json.readTree(resource("llm-extraction-config-v1.json"));
        llm.put("schemaVersion", 3);
        assertThatThrownBy(() -> MappingVersions.config(json.writeValueAsString(llm), json))
                .isInstanceOf(IllegalArgumentException.class);

        var legacy = (ObjectNode) json.readTree(resource("personal-info-config-v3.5.json"));
        MappingVersions.config(json.writeValueAsString(legacy), json).validate();
        legacy.put("engineCompatibility", "llm-extraction/1.0");
        assertThatThrownBy(() -> MappingVersions.config(json.writeValueAsString(legacy), json))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
