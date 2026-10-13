package com.cvcompass.backend.ingestion;

public enum BatchStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    PARTIAL_FAILED,
    FAILED
}
