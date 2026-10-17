package com.cvcompass.backend.ingestion.batch;

import com.cvcompass.backend.ingestion.entity.CvImportBatch;
import com.cvcompass.backend.ingestion.enums.BatchStatus;
import java.time.Instant;

public record BatchDetails(long id, int totalFiles, int successCount, int failedCount,
                           BatchStatus status, Instant createdAt, Instant completedAt) {
    static BatchDetails from(CvImportBatch b) {
        return new BatchDetails(b.getId(), b.getTotalFiles(), b.getSuccessCount(), b.getFailedCount(),
                b.getStatus(), b.getCreatedAt(), b.getCompletedAt());
    }
}
