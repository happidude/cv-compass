package com.cvcompass.backend.ingestion.enums;

public enum OcrEndReason {
    RESULT_SUCCESS,
    RESULT_PARTIAL,
    OCR_FAILED,
    INFRA_FAILURE,
    RETRIES_EXHAUSTED,
    CONFIGURATION_ERROR,
    LEASE_EXPIRED
}
