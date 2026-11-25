package com.cvcompass.backend.mapping.exception;

import org.springframework.http.HttpStatus;

public class RemapFailure extends RuntimeException {
    private final HttpStatus status;

    public RemapFailure(HttpStatus status, String code) {
        super(code);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
