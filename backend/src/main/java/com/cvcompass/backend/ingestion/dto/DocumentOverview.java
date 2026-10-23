package com.cvcompass.backend.ingestion.dto;

/** A document exists only after ingestion commits. Failed files remain batch items. */
public record DocumentOverview(DocumentMetadata metadata, Long batchId, String ingestionStatus,
                               DocumentProcessing processing) {
}
