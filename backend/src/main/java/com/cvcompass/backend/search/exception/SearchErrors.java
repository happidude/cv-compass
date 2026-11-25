package com.cvcompass.backend.search.exception;

import com.cvcompass.backend.web.ApiError;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.cvcompass.backend.search.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SearchErrors {
    @ExceptionHandler(SearchFailure.class)
    public ResponseEntity<ApiError> invalid(SearchFailure failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        new ApiError(
                                failure.getMessage(),
                                "Invalid search parameters",
                                MDC.get("correlationId"),
                                failure.violations()));
    }
}
