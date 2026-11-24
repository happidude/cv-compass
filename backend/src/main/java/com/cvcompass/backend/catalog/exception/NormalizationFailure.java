package com.cvcompass.backend.catalog.exception;

import org.springframework.http.HttpStatus;

public class NormalizationFailure extends RuntimeException {
    private final int status;

    public NormalizationFailure(HttpStatus status, String code) {
        super(code);
        this.status = status.value();
    }

    public int status() {
        return status;
    }
}
