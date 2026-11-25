package com.cvcompass.backend.search.exception;

import com.cvcompass.backend.web.FieldViolation;
import java.util.List;

public class SearchFailure extends RuntimeException {
    private final List<FieldViolation> violations;

    public SearchFailure(String field, String message) {
        super("VALIDATION_ERROR");
        violations = List.of(new FieldViolation(field, message));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
