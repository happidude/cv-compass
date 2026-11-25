package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.mapping.dto.LlmExtractionDefinition;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.service.MappingRemoteFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared synthetic inputs and normalization for the HTTP and offline serialization tests. */
final class LlmRemoteContractFixtures {
    static final String AT = "2026-01-01T00:00:00Z";
    static final ObjectMapper JSON = MappingRemoteFixtures.JSON;
    static final Path FIXTURES = Path.of("../frontend/tests/fixtures/llm-remote");

    private LlmRemoteContractFixtures() {}

    static LlmExtractionDefinition definition() {
        ObjectNode definition = JSON.valueToTree(MappingRemoteFixtures.definition());
        definition.put("revision", "synthetic-llm-contract-v1");
        ((ObjectNode) definition.path("fields").path("email")).put("enabled", false);
        return (LlmExtractionDefinition) MappingVersions.config(definition.toString(), JSON);
    }

    static ObjectNode compact(String hash, boolean subsequent) {
        var body = MappingRemoteFixtures.result(hash);
        if (subsequent) {
            ((ObjectNode) body.path("personal"))
                    .putObject("fullName")
                    .put("value", "Synthetic Reviewer")
                    .set("evidence", MappingRemoteFixtures.evidence("Synthetic Reviewer"));
        }
        var rows = body.withArray("experiences");
        var base = (ObjectNode) rows.get(0);
        var ongoing = base.deepCopy().putNull("end").put("ongoing", true);
        rows.add(ongoing);
        rows.add(base.deepCopy().putNull("end"));
        var titleOnly = base.deepCopy().putNull("company").putNull("start").putNull("end");
        ((ObjectNode) titleOnly.path("evidence")).remove(List.of("company", "period"));
        rows.add(titleOnly);
        var empty = titleOnly.deepCopy().putNull("title");
        empty.putObject("evidence");
        rows.add(empty);
        body.withArray("issues").addObject().put("code", "SYNTHETIC_WARNING").putNull("field");
        return body;
    }

    static JsonNode normalize(JsonNode input) {
        if (input.isObject()) {
            var out = JSON.createObjectNode();
            for (String key : input.propertyNames()) {
                var value = input.path(key);
                if (value.isNumber()
                        && List.of("id", "documentId", "extractionId", "configId", "resultId")
                                .contains(key)) out.put(key, 1);
                else if (value.isString() && List.of("at", "importedAt").contains(key))
                    out.put(key, AT);
                else if (key.equals("inputSha256") && value.isString())
                    out.put(key, "a".repeat(64));
                else if (key.equals("profileRevision") && value.isString())
                    out.put(key, "synthetic-profile");
                else if (value.isString()
                        && value.asString().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                    out.put(key, "00000000-0000-4000-8000-000000000001");
                else out.set(key, normalize(value));
            }
            return out;
        }
        if (input.isArray()) {
            var values = new ArrayList<JsonNode>();
            input.forEach(value -> values.add(normalize(value)));
            // Database UUID ordering is random; preserve source-row identity via data.localId.
            if (!values.isEmpty()
                    && values.stream().allMatch(value -> value.has("data") || value.has("reason")))
                values.sort(
                        Comparator.comparing(
                                value ->
                                        value.path("kind").asString("")
                                                + value.path("data").path("localId").asString("")
                                                + value.path("reason").asString("")));
            var out = JSON.createArrayNode();
            values.forEach(out::add);
            return out;
        }
        if (input.isString()
                && input.asString().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            return JSON.getNodeFactory().stringNode("00000000-0000-4000-8000-000000000001");
        return input;
    }

    static void assertFixture(String name, JsonNode response) throws Exception {
        assertFixture(name, response, FIXTURES);
    }

    static void assertFixture(String name, JsonNode response, Path fixtures) throws Exception {
        var actual = normalize(JSON.readTree(JSON.writeValueAsString(response)));
        Path generated = Path.of("build/llm-remote-fixtures", name + ".json");
        Files.createDirectories(generated.getParent());
        Files.writeString(
                generated, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        Path expected = fixtures.resolve(name + ".json");
        assertThat(Files.exists(expected))
                .withFailMessage(
                        "Thiếu fixture %s; kiểm tra JSON sinh tại %s rồi cập nhật frontend/tests/fixtures/llm-remote bằng output backend.",
                        expected, generated)
                .isTrue();
        assertThat(actual)
                .withFailMessage(
                        "Fixture %s lệch serialization backend; kiểm tra %s, cập nhật fixture từ output backend rồi chạy lại integration và Vitest.",
                        expected, generated)
                .isEqualTo(JSON.readTree(Files.readString(expected)));
    }
}
