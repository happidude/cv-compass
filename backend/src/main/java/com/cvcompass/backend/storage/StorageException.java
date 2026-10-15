package com.cvcompass.backend.storage;

/** Safe summary for services; technical causes are retained for internal diagnostics only. */
public class StorageException extends RuntimeException {
    public enum Reason { INVALID_KEY, NOT_FOUND, UNAVAILABLE, WRITE_FAILED, READ_FAILED, DELETE_FAILED }

    private final Reason reason;

    public StorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public StorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
