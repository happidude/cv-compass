package com.cvcompass.backend.web.error;

import com.cvcompass.backend.web.ApiError;
import com.cvcompass.backend.web.FieldViolation;
import com.cvcompass.backend.web.logging.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers); // Preserve Allow, Accept and other Spring MVC headers.
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (status.is5xxServerError()) {
            logUnexpected(exception);
        }
        return super.handleExceptionInternal(exception, error(exception, status, request),
                responseHeaders, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        logUnexpected(exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(exception, HttpStatus.INTERNAL_SERVER_ERROR, request));
    }

    private ApiError error(Exception exception, HttpStatusCode status, WebRequest request) {
        var correlationId = (String) request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (status.is5xxServerError()) {
            return new ApiError("INTERNAL_ERROR", "An unexpected server error occurred", correlationId, List.of());
        }
        List<FieldViolation> details = new ArrayList<>();
        if (exception instanceof MethodArgumentNotValidException validation) {
            validation.getBindingResult().getFieldErrors().forEach(field -> details.add(
                    new FieldViolation(field.getField(), validationMessage(field.getDefaultMessage()))));
            validation.getBindingResult().getGlobalErrors().forEach(global -> details.add(
                    new FieldViolation(null, validationMessage(global.getDefaultMessage()))));
        } else if (exception instanceof HandlerMethodValidationException validation) {
            validation.getParameterValidationResults().forEach(parameter -> {
                String name = parameter.getMethodParameter().getParameterName();
                if (name == null) {
                    name = "parameter" + parameter.getMethodParameter().getParameterIndex();
                }
                for (var violation : parameter.getResolvableErrors()) {
                    details.add(new FieldViolation(name, validationMessage(violation.getDefaultMessage())));
                }
            });
        }
        if (!details.isEmpty()) {
            return new ApiError("VALIDATION_ERROR", "Invalid request", correlationId, details);
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return new ApiError("INVALID_REQUEST_BODY", "Request body is missing or malformed", correlationId, List.of());
        }
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "REQUEST_ERROR";
        };
        var httpStatus = HttpStatus.resolve(status.value());
        String message = httpStatus == null ? "Request could not be processed" : httpStatus.getReasonPhrase();
        return new ApiError(code, message, correlationId, List.of());
    }

    private String validationMessage(String message) {
        return message == null ? "Invalid value" : message;
    }

    private void logUnexpected(Exception exception) {
        // Exception messages/causes can contain SQL, request values or credentials.
        // Log the type and stack locations, never the exception or its message.
        log.error("Unexpected request failure type={}, stack={}", exception.getClass().getName(),
                Arrays.toString(exception.getStackTrace()));
    }
}
