package com.cvcompass.backend.ingestion.dto;

import java.util.UUID;

/** Trusted service caller supplies a token HASH, not an authentication credential. HTTP auth is deferred. */
public record OcrClaimRequest(String workerId, UUID claimRequestId, String leaseTokenHash, String profileRevision) {
    public OcrClaimRequest {
        if (workerId == null || !workerId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}") || claimRequestId == null
                || leaseTokenHash == null || !leaseTokenHash.matches("[0-9a-f]{64}")
                || profileRevision == null || !profileRevision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid OCR claim");
        }
    }
    @Override public String toString() { return "OcrClaimRequest[workerId=" + workerId + ", claimRequestId=" + claimRequestId + "]"; }
}
