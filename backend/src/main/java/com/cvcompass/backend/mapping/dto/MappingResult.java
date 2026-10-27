package com.cvcompass.backend.mapping.dto;

import java.util.List;

public record MappingResult(
        int schemaVersion,
        long extractionId,
        String ocrSchemaVersion,
        long configId,
        String configRevision,
        String engineVersion,
        String quality,
        boolean incompleteSource,
        String reason,
        List<Value> values,
        String inputSha256,
        long documentId,
        String profileRevision) {
    public MappingResult {
        values = List.copyOf(values);
    }

    public MappingResult(
            int schemaVersion,
            long extractionId,
            String ocrSchemaVersion,
            long configId,
            String configRevision,
            String engineVersion,
            String quality,
            boolean incompleteSource,
            String reason,
            List<Value> values) {
        this(
                schemaVersion,
                extractionId,
                ocrSchemaVersion,
                configId,
                configRevision,
                engineVersion,
                quality,
                incompleteSource,
                reason,
                values,
                null,
                0,
                null);
    }

    public record Value(String field, String value, Evidence evidence) {}

    /** Offsets are [start,end) Unicode code points in this page's unchanged raw_text. */
    public record Evidence(long extractionId, int page, int start, int end, String text) {}
}
