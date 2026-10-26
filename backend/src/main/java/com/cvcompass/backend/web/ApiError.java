package com.cvcompass.backend.web;

import java.util.List;

public record ApiError(
        String code, String message, String correlationId, List<FieldViolation> details) {
    public ApiError {
        details = List.copyOf(details);
    }
}
