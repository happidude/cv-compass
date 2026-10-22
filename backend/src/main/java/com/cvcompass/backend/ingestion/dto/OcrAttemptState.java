package com.cvcompass.backend.ingestion.dto;

import com.cvcompass.backend.ingestion.enums.OcrAttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record OcrAttemptState(long id, UUID publicId, long jobId, int attemptNo, String workerId,
        UUID claimRequestId, String claimBodySha256, String leaseTokenHash, Instant leaseExpiresAt,
        Instant hardDeadlineAt, Instant reclaimAfter, OcrAttemptStatus status, String outcomeSha256) {
    @Override public String toString() { return "OcrAttemptState[publicId=" + publicId + ", status=" + status + "]"; }
}
