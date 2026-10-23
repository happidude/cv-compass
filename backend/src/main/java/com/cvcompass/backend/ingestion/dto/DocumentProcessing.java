package com.cvcompass.backend.ingestion.dto;

import java.time.Instant;

/** No worker identity, lease proof, admission snapshot or internal error message. */
public record DocumentProcessing(Long jobId, String status, Integer attemptsStarted, Integer maxAttempts,
                                 Instant nextAttemptAt, Instant startedAt, Instant completedAt,
                                 String endReason, String errorCode, String resultAvailability) {
}
