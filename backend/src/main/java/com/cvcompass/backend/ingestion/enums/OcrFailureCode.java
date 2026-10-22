package com.cvcompass.backend.ingestion.enums;

/** Retry classification is protocol policy, not an operator bypass flag. */
public enum OcrFailureCode {
    DOWNLOAD_UNAVAILABLE(true), SUPERVISOR_TIMEOUT(true), LEASE_EXPIRED(true),
    OUTPUT_NOT_DELIVERED(false), CONFIGURATION_ERROR(false), INPUT_HASH_MISMATCH(false),
    RESOURCE_LIMIT(false), INVALID_RESULT(false);

    private final boolean retryable;
    OcrFailureCode(boolean retryable) { this.retryable = retryable; }
    public boolean isRetryable() { return retryable; }
}
