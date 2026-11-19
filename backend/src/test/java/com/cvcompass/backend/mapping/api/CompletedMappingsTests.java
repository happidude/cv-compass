package com.cvcompass.backend.mapping.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CompletedMappingsTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final CompletedMappings mappings = new CompletedMappings(null, json);

    private ObjectNode body(int schema, String engine) {
        var body = json.createObjectNode();
        body.put("schemaVersion", schema).put("engineVersion", engine);
        body.put("extractionId", 1).put("configId", 2).put("documentId", 3);
        body.put("ocrSchemaVersion", "cv-compass.ocr/2.0");
        body.put("configRevision", "synthetic").put("quality", "NEEDS_REVIEW");
        body.put("incompleteSource", false).putNull("reason");
        body.put("inputSha256", "a".repeat(64));
        body.put("profileRevision", "synthetic").put("rulesRevision", "synthetic");
        body.put("taxonomyRevision", "synthetic");
        for (String name : List.of("values", "experiences", "issues")) body.putArray(name);
        if (schema == 3) {
            var personal = body.putObject("personalInfo");
            for (String name : PersonalInfoDefinition.fieldNames(engine)) {
                var field = personal.putObject(name);
                field.put("enabled", true).putNull("raw").putNull("normalized");
                field.put("state", "NOT_FOUND");
                for (String array : List.of("alternatives", "evidence", "issues"))
                    field.putArray(array);
            }
        }
        return body;
    }

    private CompletedMappings.Result result(int schema, ObjectNode body) {
        return new CompletedMappings.Result(1, schema, "NEEDS_REVIEW", body.toString(), 1, 2, 3);
    }

    @Test
    void identifiesExperienceAndCanonicalPersonalVersions() {
        assertThat(mappings.publishable(result(2, body(2, "experience-mapping/2.0"))))
                .contains(new PublishableMapping(2, "experience-mapping/2.0", false, false));
        for (int minor = 0; minor <= 5; minor++) {
            String engine = "personal-info-mapping/3." + minor;
            assertThat(mappings.publishable(result(3, body(3, engine))))
                    .contains(new PublishableMapping(3, engine, true, minor >= 3));
        }
    }

    @Test
    void everySupportedPersonalEngineCanBePublished() throws Exception {
        int checked = 0;
        for (var constant : PersonalInfoDefinition.class.getDeclaredFields()) {
            if (constant.getType() != String.class || !Modifier.isStatic(constant.getModifiers()))
                continue;
            String engine = (String) constant.get(null);
            if (!PersonalInfoDefinition.supportsEngine(engine)) continue;
            assertThat(mappings.publishable(result(3, body(3, engine)))).isPresent();
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(6);
    }

    @Test
    void unsupportedVersionsAndColumnMismatchAreEmpty() {
        for (var result :
                List.of(
                        result(1, body(1, "literal-mapping/1.0")),
                        result(3, body(3, "personal-info-mapping/9.9")),
                        result(2, body(2, "experience-mapping/9.9")),
                        result(2, body(3, PersonalInfoDefinition.ENGINE_VERSION)))) {
            assertThat(mappings.supportsPublicationVersion(result)).isFalse();
            assertThat(mappings.publishable(result)).isEmpty();
        }
    }

    @Test
    void malformedSupportedEnvelopesAreEmptyWithoutThrowing() {
        var missing = body(3, PersonalInfoDefinition.ENGINE_VERSION);
        missing.remove("personalInfo");
        var gender = body(3, PersonalInfoDefinition.ENGINE_VERSION);
        ((ObjectNode) gender.path("personalInfo").path("gender"))
                .put("state", "PROPOSED")
                .put("raw", "Synthetic")
                .put("normalized", "UNKNOWN");
        var wrongType = body(3, PersonalInfoDefinition.ENGINE_VERSION);
        wrongType.put("experiences", "synthetic invalid array");
        var nullGender = body(3, PersonalInfoDefinition.ENGINE_VERSION);
        ((ObjectNode) nullGender.path("personalInfo")).putNull("gender");
        for (var body : List.of(missing, gender, wrongType, nullGender)) {
            var result = result(3, body);
            assertThat(mappings.supportsPublicationVersion(result)).isTrue();
            assertThat(mappings.publishable(result)).isEmpty();
        }
        var malformed = new CompletedMappings.Result(1, 3, "NEEDS_REVIEW", "{", 1, 2, 3);
        assertThat(mappings.publishable(malformed)).isEmpty();
    }

    @Test
    void defaultJacksonReaderIgnoresExtraEnvelopeProperties() {
        var body = body(3, PersonalInfoDefinition.ENGINE_VERSION);
        body.put("syntheticExtra", true);
        assertThat(mappings.publishable(result(3, body))).isPresent();
    }
}
