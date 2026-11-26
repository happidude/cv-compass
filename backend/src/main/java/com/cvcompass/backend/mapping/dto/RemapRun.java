package com.cvcompass.backend.mapping.dto;

import java.time.Instant;

public record RemapRun(
        long id,
        String scope,
        RemapRequest scopeValue,
        long configId,
        String state,
        long cursorExtractionId,
        long upperExtractionId,
        long selected,
        long queued,
        long skipped,
        long completed,
        long failed,
        long pending,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {}
