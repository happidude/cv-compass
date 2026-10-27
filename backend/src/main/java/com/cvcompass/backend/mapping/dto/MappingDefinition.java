package com.cvcompass.backend.mapping.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record MappingDefinition(int schemaVersion, String engineCompatibility, List<Field> fields) {
    public static final String ENGINE_VERSION = "literal-mapping/1.0";

    public MappingDefinition(int schemaVersion, List<Field> fields) {
        this(schemaVersion, ENGINE_VERSION, fields);
    }

    public MappingDefinition {
        if (fields != null) fields = List.copyOf(fields);
    }

    public void validate() {
        if (schemaVersion != 1
                || !ENGINE_VERSION.equals(engineCompatibility)
                || fields == null
                || fields.isEmpty()
                || fields.size() > 32) {
            throw new IllegalArgumentException("Unsupported mapping definition");
        }
        Set<String> keys = new HashSet<>();
        for (Field field : fields) {
            if (field == null
                    || field.key() == null
                    || !field.key().matches("[a-z][a-z0-9_]{0,63}")
                    || !keys.add(field.key())
                    || !"STRING".equals(field.type())
                    || !"PAGE".equals(field.scope())
                    || !Set.of("FIRST_LINE", "LITERAL")
                            .contains(field.extractor() == null ? "" : field.extractor())
                    || ("LITERAL".equals(field.extractor())
                            && (field.argument() == null
                                    || field.argument().isBlank()
                                    || field.argument().codePointCount(0, field.argument().length())
                                            > 256))
                    || ("FIRST_LINE".equals(field.extractor()) && field.argument() != null)) {
                throw new IllegalArgumentException("Unsupported mapping field");
            }
        }
    }

    public record Field(String key, String type, String scope, String extractor, String argument) {}
}
