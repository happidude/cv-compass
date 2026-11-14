package com.cvcompass.backend.mapping.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record LlmExtractionDefinition(
        int schemaVersion,
        String engineCompatibility,
        String revision,
        @JsonProperty("fields") Map<String, Field> fieldDefinitions,
        Other other)
        implements MappingConfig {
    public static final String ENGINE_VERSION = "llm-extraction/1.0";
    public static final Set<String> FIELD_NAMES =
            Set.of(
                    "fullName",
                    "email",
                    "phone",
                    "location",
                    "summary",
                    "gender",
                    "dateOfBirth",
                    "experiences");

    public LlmExtractionDefinition {
        fieldDefinitions =
                fieldDefinitions == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.TreeMap<>(fieldDefinitions));
    }

    @Override
    public void validate() {
        if (schemaVersion != 4
                || !ENGINE_VERSION.equals(engineCompatibility)
                || revision == null
                || !revision.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || !fieldDefinitions.keySet().equals(FIELD_NAMES)
                || other == null
                || other.maxItems() < 0
                || other.maxItems() > 50) throw invalid();
        text(other.description());
        fieldDefinitions.forEach(
                (name, field) -> {
                    if (field == null || field.aliases() == null) throw invalid();
                    String expected =
                            switch (name) {
                                case "gender" -> "ENUM_GENDER";
                                case "dateOfBirth" -> "DATE_PARTIAL";
                                case "experiences" -> "EXPERIENCE_LIST";
                                default -> "STRING";
                            };
                    if (!expected.equals(field.type())) throw invalid();
                    text(field.description());
                    field.aliases().forEach(LlmExtractionDefinition::text);
                });
    }

    private static void text(String value) {
        if (value == null || value.codePointCount(0, value.length()) > 2000) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid LLM mapping configuration");
    }

    public record Field(boolean enabled, String description, String type, List<String> aliases) {
        public Field {
            aliases = aliases == null ? null : List.copyOf(aliases);
        }
    }

    public record Other(boolean enabled, String description, int maxItems) {}
}
