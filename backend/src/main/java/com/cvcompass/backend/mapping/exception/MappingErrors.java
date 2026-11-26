package com.cvcompass.backend.mapping.exception;

import com.cvcompass.backend.web.ApiError;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.cvcompass.backend.mapping.controller")
public class MappingErrors {
    @ExceptionHandler(RemapFailure.class)
    public ResponseEntity<ApiError> handle(RemapFailure failure) {
        return ResponseEntity.status(failure.status())
                .body(
                        new ApiError(
                                failure.getMessage(),
                                "Request could not be completed",
                                MDC.get("correlationId"),
                                List.of()));
    }
}
