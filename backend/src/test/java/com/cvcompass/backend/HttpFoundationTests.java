package com.cvcompass.backend;

import com.cvcompass.backend.web.ApiResponse;
import com.cvcompass.backend.web.error.GlobalExceptionHandler;
import com.cvcompass.backend.web.logging.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC slice only: no datasource, Flyway, repositories, or real business endpoints. */
@WebMvcTest(controllers = HttpFoundationTests.TestController.class)
@Import({HttpFoundationTests.TestController.class, GlobalExceptionHandler.class, CorrelationIdFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class HttpFoundationTests {
    private static final String HEADER = CorrelationIdFilter.HEADER_NAME;
    private static final String SECRET = "synthetic-secret-sql-credential";
    @Autowired MockMvc mvc;

    @AfterEach
    void requestDoesNotLeakMdc() {
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void validDtoCanUseOptionalEnvelope() throws Exception {
        mvc.perform(post("/_test/validation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"fixture\",\"tags\":[\"one\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.label").value("fixture"))
                .andExpect(jsonPath("$.data.tags[0]").value("one"))
                .andExpect(header().exists(HEADER));
    }

    @Test
    void validationProvidesFieldErrorsWithoutRejectedValues() throws Exception {
        mvc.perform(post("/_test/validation").header(HEADER, "validation-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\" \",\"tags\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(jsonPath("$.correlationId").value("validation-123"))
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder("label", "tags")))
                .andExpect(jsonPath("$.details[*].message", everyItem(not(emptyOrNullString()))))
                .andExpect(jsonPath("$.details[*].rejectedValue").doesNotExist());
    }

    @Test
    void sizeConstraintsAreValidated() throws Exception {
        mvc.perform(post("/_test/validation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + "x".repeat(41) + "\",\"tags\":[\"a\",\"b\",\"c\",\"d\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder("label", "tags")));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void malformedMissingAndWrongTypeBodiesAreSafe(String body) throws Exception {
        var result = mvc.perform(post("/_test/validation").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET);
        assertThat(result.getResponse().getContentAsString())
                .contains(result.getResponse().getHeader(HEADER));
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "{", "{\"label\":{},\"tags\":[\"" + SECRET + "\"]}");
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        mvc.perform(get("/_test/validation"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeIsStandardized() throws Exception {
        mvc.perform(post("/_test/validation").contentType(MediaType.TEXT_PLAIN).content(SECRET))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void queryParameterValidationHasFieldDetails() throws Exception {
        mvc.perform(get("/_test/count").param("count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("count"));
    }

    @Test
    void invalidParameterTypeDoesNotExposeItsValue() throws Exception {
        var response = mvc.perform(get("/_test/count").param("count", SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void missingRouteHasStandardNotFoundResponse() throws Exception {
        mvc.perform(get("/_test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedFailureIsSafeAndHasDiagnosticLog(CapturedOutput output) throws Exception {
        var response = mvc.perform(get("/_test/failure").header(HEADER, "failure-123"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").value("failure-123"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(SECRET, "IllegalStateException", "stack");
        assertThat(output.getOut()).contains("IllegalStateException", "failure-123").doesNotContain(SECRET);
        assertThat(output.getOut()).contains("applicationStack=com.cvcompass.backend.", "HttpFoundationTests");
        assertThat(response.getContentAsString()).doesNotContain("applicationStack", "HttpFoundationTests");
    }

    @Test
    void frameworkStatusIsPreservedWithoutExposingReason() throws Exception {
        var response = mvc.perform(get("/_test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void generatedIdIsVisibleInRequestMdcAndResponseAndChangesBetweenRequests() throws Exception {
        var first = mvc.perform(get("/_test/mdc").header(HEADER, "first-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").value("first-request"))
                .andReturn().getResponse();
        assertThat(first.getHeader(HEADER)).isEqualTo("first-request");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        var second = mvc.perform(get("/_test/mdc"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String id = second.getHeader(HEADER);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(second.getContentAsString()).contains(id).doesNotContain("first-request");
    }

    @Test
    void validMaximumLengthIdIsPropagated() throws Exception {
        String id = "A" + "x._-".repeat(15) + "abc";
        assertThat(id).hasSize(64);
        mvc.perform(get("/_test/mdc").header(HEADER, id))
                .andExpect(header().string(HEADER, id))
                .andExpect(jsonPath("$.correlationId").value(id));
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void unsafeCorrelationIdsAreReplaced(String supplied) throws Exception {
        var response = mvc.perform(get("/_test/mdc").header(HEADER, supplied))
                .andExpect(status().isOk()).andReturn().getResponse();
        String generated = response.getHeader(HEADER);
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        assertThat(generated).isNotEqualTo(supplied);
    }

    static Stream<String> invalidIds() {
        return Stream.of("", "x".repeat(65), "line\r\ninjection", " has-space", "<script>", "é", "one,two");
    }

    @Test
    void multipleCorrelationHeadersAreReplaced() throws Exception {
        String id = mvc.perform(get("/_test/mdc").header(HEADER, "first", "second"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HEADER);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    }

    @Test
    void noContentResponseIsNotWrapped() throws Exception {
        mvc.perform(delete("/_test/no-content"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().exists(HEADER));
    }

    record ValidationExample(@NotBlank @Size(max = 40) String label,
                             @NotNull @Size(min = 1, max = 3) List<@NotBlank String> tags) {
    }

    @RestController
    static class TestController {
        @PostMapping(value = "/_test/validation", consumes = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<ApiResponse<ValidationExample>> validation(@Valid @RequestBody ValidationExample example) {
            return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(example));
        }

        @GetMapping("/_test/count")
        Map<String, Integer> count(@RequestParam @Min(1) int count) {
            return Map.of("count", count);
        }

        @GetMapping("/_test/failure")
        void failure() {
            var failure = new IllegalStateException(SECRET, new RuntimeException(SECRET));
            failure.addSuppressed(new RuntimeException(SECRET));
            throw failure;
        }

        @GetMapping("/_test/conflict")
        void conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SECRET);
        }

        @GetMapping("/_test/mdc")
        Map<String, String> mdc() {
            return Map.of("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        }

        @DeleteMapping("/_test/no-content")
        ResponseEntity<Void> noContent() {
            return ResponseEntity.noContent().build();
        }
    }
}
