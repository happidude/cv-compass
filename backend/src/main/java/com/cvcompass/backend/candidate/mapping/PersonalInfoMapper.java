package com.cvcompass.backend.candidate.mapping;

import java.text.Normalizer;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure conversion; publication keeps ownership, locks and review decisions. */
public final class PersonalInfoMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final Set<String> FIELDS =
            Set.of("fullName", "email", "phone", "location", "summary", "gender");

    private PersonalInfoMapper() {}

    public static ObjectNode patch(JsonNode result, JsonNode basic) {
        var patch = JSON.createObjectNode();
        for (String name : FIELDS) {
            var field = result.path("personalInfo").path(name);
            if (field.path("enabled").asBoolean()
                    && "PROPOSED".equals(field.path("state").asString())
                    && field.path("raw").isString()
                    && field.path("normalized").isString()
                    && !field.path("evidence").isEmpty()) {
                String value =
                        name.equals("gender")
                                ? field.path("normalized").asString()
                                : field.path("raw").asString();
                if (!same(name, basic.path(name), value)) patch.put(name, value);
            }
        }
        return patch;
    }

    public static boolean same(String name, JsonNode old, String value) {
        if (!old.isString()) return false;
        return canonical(name, old.asString()).equals(canonical(name, value));
    }

    private static String canonical(String name, String value) {
        if (name.equals("summary"))
            return Normalizer.normalize(value, Normalizer.Form.NFC)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .strip();
        String clean =
                Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        if (name.equals("phone")) return clean.replaceAll("[\\s().-]", "");
        if (name.equals("email")) {
            int at = clean.lastIndexOf('@');
            return at < 0
                    ? clean
                    : clean.substring(0, at + 1)
                            + clean.substring(at + 1).toLowerCase(java.util.Locale.ROOT);
        }
        return clean;
    }

    public static ObjectNode merge(JsonNode basic, JsonNode patch) {
        ObjectNode merged =
                basic.isObject() ? (ObjectNode) basic.deepCopy() : JSON.createObjectNode();
        for (String field : patch.propertyNames()) merged.set(field, patch.get(field));
        return merged;
    }

    public static ObjectNode metadata(JsonNode result, long documentId, long resultId) {
        var fields = JSON.createObjectNode();
        for (String name : FIELDS) {
            if (!result.path("personalInfo").path(name).isObject()) continue;
            var field = (ObjectNode) result.path("personalInfo").path(name).deepCopy();
            field.put("documentId", documentId)
                    .put("resultId", resultId)
                    .put("configRevision", result.path("configRevision").asString());
            fields.set(name, field);
        }
        return JSON.createObjectNode().put("schemaVersion", 1).set("fields", fields);
    }

    public static ObjectNode extensions(
            JsonNode existing,
            JsonNode metadata,
            java.util.Collection<String> changed,
            boolean user) {
        ObjectNode extensions =
                existing.isObject()
                        ? (ObjectNode) existing.deepCopy()
                        : JSON.createObjectNode().put("schemaVersion", 1);
        ObjectNode personal =
                extensions.path("personalInfo").isObject()
                        ? (ObjectNode) extensions.path("personalInfo").deepCopy()
                        : JSON.createObjectNode().put("schemaVersion", 1);
        ObjectNode fields =
                personal.path("fields").isObject()
                        ? (ObjectNode) personal.path("fields").deepCopy()
                        : JSON.createObjectNode();
        for (String name : changed) {
            if (user)
                fields.set(
                        name,
                        JSON.createObjectNode().put("origin", "USER").put("reviewState", "EDITED"));
            else if (metadata.path("fields").path(name).isObject())
                fields.set(name, metadata.path("fields").path(name));
        }
        personal.set("fields", fields);
        extensions.set("personalInfo", personal);
        return extensions;
    }
}
