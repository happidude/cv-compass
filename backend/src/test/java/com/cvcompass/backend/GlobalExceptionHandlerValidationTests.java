package com.cvcompass.backend;

import com.cvcompass.backend.web.ApiError;
import com.cvcompass.backend.web.FieldViolation;
import com.cvcompass.backend.web.error.GlobalExceptionHandler;
import com.cvcompass.backend.web.logging.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerValidationTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void bodyValidationWithEmptyErrorsStillUsesValidationCode() throws Exception {
        var errors = new BeanPropertyBindingResult(new Object(), "example");
        var exception = new MethodArgumentNotValidException(parameter(), errors);
        assertValidationError(exception, List.of());
    }

    @Test
    void methodValidationWithEmptyParameterErrorsStillUsesValidationCode() throws Exception {
        var result = mock(MethodValidationResult.class);
        when(result.isForReturnValue()).thenReturn(false);
        when(result.getParameterValidationResults()).thenReturn(List.of());
        assertValidationError(new HandlerMethodValidationException(result), List.of());
    }

    @Test
    void crossParameterValidationWithoutFieldDetailsStillUsesValidationCode() throws Exception {
        var result = MethodValidationResult.create(this,
                getClass().getDeclaredMethod("validationTarget", Object.class), List.of(),
                List.of(new DefaultMessageSourceResolvable(new String[] {"InconsistentParameters"}, "Parameters are inconsistent")));
        assertThat(result.getParameterValidationResults()).isEmpty();
        assertThat(result.getCrossParameterValidationResults()).hasSize(1);
        assertValidationError(new HandlerMethodValidationException(result), List.of());
    }

    @Test
    void objectLevelValidationRetainsGlobalDetailsWithoutInventingAField() throws Exception {
        var errors = new BeanPropertyBindingResult(new Object(), "example");
        errors.reject("InconsistentObject", "Values are inconsistent");
        assertThat(errors.getFieldErrors()).isEmpty();
        assertValidationError(new MethodArgumentNotValidException(parameter(), errors),
                List.of(new FieldViolation(null, "Values are inconsistent")));
    }

    @Test
    void returnValueValidationRemainsGenericServerError() throws Exception {
        var result = mock(MethodValidationResult.class);
        when(result.isForReturnValue()).thenReturn(true);
        var response = handler.handleException(new HandlerMethodValidationException(result), request());
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiError("INTERNAL_ERROR",
                "An unexpected server error occurred", "regression-123", List.of()));
    }

    private void assertValidationError(Exception exception, List<FieldViolation> details) throws Exception {
        var response = handler.handleException(exception, request());
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(new ApiError("VALIDATION_ERROR", "Invalid request",
                "regression-123", details));
    }

    private ServletWebRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "regression-123");
        return new ServletWebRequest(request, new MockHttpServletResponse());
    }

    private MethodParameter parameter() throws Exception {
        return new MethodParameter(getClass().getDeclaredMethod("validationTarget", Object.class), 0);
    }

    void validationTarget(Object example) {
    }
}
