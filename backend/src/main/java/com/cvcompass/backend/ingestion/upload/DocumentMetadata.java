package com.cvcompass.backend.ingestion.upload;

import com.cvcompass.backend.ingestion.entity.CvDocument;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import java.time.Instant;

/** No physical path or storage-provider details are exposed. */
public record DocumentMetadata(Long id, String originalFilename, long sizeBytes, String mimeType,
                               String sha256, DocumentStatus status, Instant createdAt) {
    public static DocumentMetadata from(CvDocument document) {
        return new DocumentMetadata(document.getId(), document.getOriginalFilename(), document.getFileSize(),
                document.getMimeType(), document.getSha256(), document.getStatus(), document.getCreatedAt());
    }
}
