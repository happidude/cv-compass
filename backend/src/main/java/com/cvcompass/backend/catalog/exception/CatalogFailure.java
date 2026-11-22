package com.cvcompass.backend.catalog.exception;

import com.cvcompass.backend.web.FieldViolation;
import java.util.List;

public class CatalogFailure extends RuntimeException {
    private final List<FieldViolation> violations;

    public CatalogFailure(List<FieldViolation> violations) {
        this("VALIDATION_ERROR", violations);
    }

    public CatalogFailure(String code, List<FieldViolation> violations) {
        super(code);
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
