package com.cvcompass.backend.mapping.dto;

import java.util.List;

public interface MappingOutcome {
    int schemaVersion();

    long extractionId();

    String ocrSchemaVersion();

    long configId();

    String configRevision();

    String engineVersion();

    String quality();

    boolean incompleteSource();

    String reason();

    List<MappingResult.Value> values();

    String inputSha256();

    long documentId();

    String profileRevision();
}
