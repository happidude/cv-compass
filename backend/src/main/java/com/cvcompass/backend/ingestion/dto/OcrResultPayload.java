package com.cvcompass.backend.ingestion.dto;

import com.cvcompass.backend.ingestion.enums.ExtractionMethod;

/** Internal validated persistence packet; toString never exposes OCR text. */
public record OcrResultPayload(
        String profileRevision,
        String schemaVersion,
        String requestId,
        String documentId,
        String status,
        ExtractionMethod method,
        String resultJson,
        String provenanceJson,
        String digest) {
    @Override
    public String toString() {
        return "OcrResultPayload[status=" + status + "]";
    }
}
