package com.cvcompass.backend.catalog.exception;

public class NormalizationFailure extends RuntimeException {
    private final int status;

    public NormalizationFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
