package com.cvcompass.backend.catalog.exception;

import com.cvcompass.backend.web.ApiError;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.cvcompass.backend.catalog.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogErrors {
    @ExceptionHandler(CatalogFailure.class)
    public ResponseEntity<ApiError> invalid(CatalogFailure failure) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(
                        new ApiError(
                                failure.getMessage(),
                                "Invalid catalog seed",
                                MDC.get("correlationId"),
                                failure.violations()));
    }
}
