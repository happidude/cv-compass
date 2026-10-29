package com.cvcompass.backend.candidate.exception;

import com.cvcompass.backend.web.ApiError;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.cvcompass.backend.candidate")
public class CandidateErrors {
    @ExceptionHandler(CandidateFailure.class)
    public ResponseEntity<ApiError> handle(CandidateFailure ex) {
        return ResponseEntity.status(ex.status())
                .body(
                        new ApiError(
                                ex.getMessage(),
                                "Request could not be completed",
                                null,
                                List.of()));
    }
}
