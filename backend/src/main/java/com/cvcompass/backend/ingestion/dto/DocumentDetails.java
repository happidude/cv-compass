package com.cvcompass.backend.ingestion.dto;

import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import java.time.Instant;

/** Keeps the existing metadata fields at the top level for M4 callers. */
public record DocumentDetails(
        Long id,
        String originalFilename,
        long sizeBytes,
        String mimeType,
        String sha256,
        DocumentStatus status,
        Instant createdAt,
        Long batchId,
        String ingestionStatus,
        DocumentProcessing processing,
        DocumentOcrResult ocr) {
    public static DocumentDetails from(DocumentOverview overview, DocumentOcrResult ocr) {
        var metadata = overview.metadata();
        return new DocumentDetails(
                metadata.id(),
                metadata.originalFilename(),
                metadata.sizeBytes(),
                metadata.mimeType(),
                metadata.sha256(),
                metadata.status(),
                metadata.createdAt(),
                overview.batchId(),
                overview.ingestionStatus(),
                overview.processing(),
                ocr);
    }
}
