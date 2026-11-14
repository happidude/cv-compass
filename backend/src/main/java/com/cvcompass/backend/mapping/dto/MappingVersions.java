package com.cvcompass.backend.mapping.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MappingVersions {
    private MappingVersions() {}

    public static MappingConfig config(String body, ObjectMapper json) {
        JsonNode root = json.readTree(body);
        if (!root.isObject()
                || !root.path("schemaVersion").isIntegralNumber()
                || !root.path("engineCompatibility").isString()) throw invalid();
        int version = root.path("schemaVersion").asInt(-1);
        if (version == 2) validateV2(root);
        if (version == 3) validateV3(root);
        if (version == 4) {
            root =
                    json.reader()
                            .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .with(
                                    tools.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(body);
            validateV4(root);
        }
        return switch (version) {
            case 1 -> json.readValue(body, MappingDefinition.class);
            case 2 -> json.readValue(body, ExperienceDefinition.class);
            case 3 -> {
                var definition = json.readValue(body, PersonalInfoDefinition.class);
                definition.validate();
                yield definition;
            }
            case 4 -> {
                var definition = json.readValue(body, LlmExtractionDefinition.class);
                definition.validate();
                yield definition;
            }
            default -> throw new IllegalArgumentException("Unsupported mapping config schema");
        };
    }

    public static boolean personalResultEngine(String engine) {
        return PersonalInfoDefinition.supportsEngine(engine)
                || LlmExtractionDefinition.ENGINE_VERSION.equals(engine);
    }

    public static java.util.Set<String> personalResultFields(String engine) {
        return LlmExtractionDefinition.ENGINE_VERSION.equals(engine)
                ? PersonalInfoDefinition.V33_FIELD_NAMES
                : PersonalInfoDefinition.fieldNames(engine);
    }

    private static void exact(JsonNode node, java.util.Set<String> names) {
        if (!node.isObject() || !node.propertyNames().equals(names)) throw invalid();
    }

    private static void validateV4(JsonNode root) {
        exact(
                root,
                java.util.Set.of(
                        "schemaVersion", "engineCompatibility", "revision", "fields", "other"));
        if (!root.path("revision").isString()) throw invalid();
        JsonNode fields = root.path("fields");
        exact(fields, LlmExtractionDefinition.FIELD_NAMES);
        for (JsonNode field : fields) {
            exact(field, java.util.Set.of("enabled", "description", "type", "aliases"));
            if (!field.path("enabled").isBoolean()
                    || !field.path("description").isString()
                    || !field.path("type").isString()) throw invalid();
            strings(field.path("aliases"));
        }
        JsonNode other = root.path("other");
        exact(other, java.util.Set.of("enabled", "description", "maxItems"));
        if (!other.path("enabled").isBoolean()
                || !other.path("description").isString()
                || !other.path("maxItems").isIntegralNumber()
                || !other.path("maxItems").canConvertToInt()) throw invalid();
    }

    private static void validateV2(JsonNode root) {
        keys(
                root,
                java.util.Set.of(
                        "schemaVersion",
                        "engineCompatibility",
                        "rulesRevision",
                        "taxonomyRevision",
                        "rules",
                        "titles",
                        "companies"));
        for (String field : java.util.List.of("rulesRevision", "taxonomyRevision"))
            if (!root.path(field).isString()) throw invalid();
        JsonNode rules = root.path("rules");
        if (!rules.isObject()) throw invalid();
        keys(
                rules,
                java.util.Set.of(
                        "experienceHeadings",
                        "summaryHeadings",
                        "stopHeadings",
                        "employerLabels",
                        "titleLabels",
                        "periodLabels",
                        "continuationMarkers"));
        for (String field :
                java.util.List.of(
                        "experienceHeadings",
                        "summaryHeadings",
                        "stopHeadings",
                        "employerLabels",
                        "titleLabels",
                        "periodLabels",
                        "continuationMarkers")) strings(rules.path(field));
        for (String collection : java.util.List.of("titles", "companies")) {
            JsonNode array = root.path(collection);
            if (!array.isArray()) throw invalid();
            for (JsonNode rule : array) {
                if (!rule.isObject() || !rule.path("canonical").isString()) throw invalid();
                keys(
                        rule,
                        collection.equals("titles")
                                ? java.util.Set.of(
                                        "aliases", "canonical", "functionCodes", "seniorityCode")
                                : java.util.Set.of(
                                        "aliases", "canonical", "industryCodes", "basis"));
                strings(rule.path("aliases"));
                strings(rule.path(collection.equals("titles") ? "functionCodes" : "industryCodes"));
                JsonNode optional =
                        rule.path(collection.equals("titles") ? "seniorityCode" : "basis");
                if (!optional.isMissingNode() && !optional.isNull() && !optional.isString())
                    throw invalid();
            }
        }
    }

    private static void validateV3(JsonNode root) {
        keys(
                root,
                java.util.Set.of(
                        "schemaVersion",
                        "engineCompatibility",
                        "revision",
                        "experience",
                        "personalFields",
                        "sectionHeadings",
                        "excludedRegions"));
        if (!root.path("revision").isString()) throw invalid();
        JsonNode experience = root.path("experience");
        if (!experience.isObject()
                || !experience.path("schemaVersion").isIntegralNumber()
                || !experience.path("engineCompatibility").isString()) throw invalid();
        validateV2(experience);
        JsonNode fields = root.path("personalFields");
        if (!fields.isObject()
                || !fields.propertyNames()
                        .equals(
                                PersonalInfoDefinition.fieldNames(
                                        root.path("engineCompatibility").asString())))
            throw invalid();
        for (JsonNode field : fields) {
            if (!field.isObject() || !field.path("enabled").isBoolean()) throw invalid();
            keys(field, java.util.Set.of("enabled", "labels", "priorities"));
            strings(field.path("labels"));
            strings(field.path("priorities"));
        }
        JsonNode sections = root.path("sectionHeadings");
        if (!sections.isObject()
                || !sections.propertyNames().equals(PersonalInfoDefinition.SECTIONS))
            throw invalid();
        for (JsonNode headings : sections) strings(headings);
        strings(root.path("excludedRegions"));
    }

    private static void keys(JsonNode node, java.util.Set<String> allowed) {
        if (!allowed.containsAll(node.propertyNames())) throw invalid();
    }

    private static void strings(JsonNode array) {
        if (!array.isArray()) throw invalid();
        for (JsonNode value : array) if (!value.isString()) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid mapping configuration types");
    }

    public static MappingOutcome result(String body, ObjectMapper json) {
        return switch (json.readTree(body).path("schemaVersion").asInt(-1)) {
            case 1 -> json.readValue(body, MappingResult.class);
            case 2 -> json.readValue(body, ExperienceMappingResult.class);
            case 3 -> json.readValue(body, PersonalInfoMappingResult.class);
            default -> throw new IllegalArgumentException("Unsupported mapping result schema");
        };
    }
}
