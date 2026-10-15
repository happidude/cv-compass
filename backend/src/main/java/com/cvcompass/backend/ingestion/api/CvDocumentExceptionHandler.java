package com.cvcompass.backend.ingestion.api;

import com.cvcompass.backend.ingestion.upload.DocumentNotFoundException;
import com.cvcompass.backend.ingestion.upload.UploadRejectedException;
import com.cvcompass.backend.web.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = CvDocumentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CvDocumentExceptionHandler {
    @ExceptionHandler(UploadRejectedException.class)
    public ResponseEntity<ApiError> rejected(UploadRejectedException exception, HttpServletResponse response) {
        HttpStatus status = switch (exception.reason()) {
            case FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case INVALID_PDF -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(exception.reason().name(),
                exception.getMessage(), response.getHeader("X-Correlation-ID"), List.of()));
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> notFound(DocumentNotFoundException exception, HttpServletResponse response) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND",
                exception.getMessage(), response.getHeader("X-Correlation-ID"), List.of()));
    }
}
