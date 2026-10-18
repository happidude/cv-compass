package com.cvcompass.backend.ingestion.exception;

/** Safe client-facing validation failure; never include request content or filenames. */
public class UploadRejectedException extends RuntimeException {
    public enum Reason { INVALID_MULTIPART, EMPTY_FILE, INVALID_PDF, FILE_TOO_LARGE, INVALID_FILENAME }
    private final Reason reason;

    public UploadRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
