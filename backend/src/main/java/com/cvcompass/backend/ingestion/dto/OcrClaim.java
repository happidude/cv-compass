package com.cvcompass.backend.ingestion.dto;

import java.time.Instant;
import java.util.UUID;

public record OcrClaim(long jobId, long documentId, String profileRevision, UUID attemptId,
        int attemptNo, Instant serverTime, Instant leaseExpiresAt, Instant hardDeadlineAt) { }
