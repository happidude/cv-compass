package com.cvcompass.backend.ingestion.dto;

import java.util.UUID;

public record OcrAttemptKey(UUID attemptId, String workerId, String leaseTokenHash) {
    public OcrAttemptKey {
        if (attemptId == null || workerId == null || !workerId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || leaseTokenHash == null || !leaseTokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid OCR attempt identity");
        }
    }
    @Override public String toString() { return "OcrAttemptKey[attemptId=" + attemptId + "]"; }
}
