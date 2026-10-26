package com.cvcompass.backend.ingestion.dto;

import com.cvcompass.backend.ingestion.enums.JobStatus;
import java.time.Instant;

public record OcrJobState(
        long id,
        long documentId,
        String profileRevision,
        int generation,
        int maxAttempts,
        int attemptsStarted,
        Instant nextAttemptAt,
        Long currentAttemptId,
        JobStatus status) {}
