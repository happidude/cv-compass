package com.cvcompass.backend.mapping.dto;

import java.util.List;

public interface MappingConfig {
    int schemaVersion();

    String engineCompatibility();

    void validate();

    default List<MappingDefinition.Field> fields() {
        return List.of();
    }
}
