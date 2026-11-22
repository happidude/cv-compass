package com.cvcompass.backend.catalog.controller;

import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Claim;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Submission;
import com.cvcompass.backend.catalog.exception.NormalizationFailure;
import com.cvcompass.backend.catalog.service.NormalizationService;
import com.cvcompass.backend.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/normalization/v1")
public class NormalizationController {
    private final NormalizationService service;
    private final NormalizationProperties properties;
    private final ObjectMapper json;

    public NormalizationController(
            NormalizationService service, NormalizationProperties properties, ObjectMapper json) {
        this.service = service;
        this.properties = properties;
        this.json = json;
    }

    @PostMapping("/claims")
    public ResponseEntity<Claim> claim(HttpServletRequest request) throws IOException {
        var body = body(request);
        if (!body.path("max").isIntegralNumber() || !body.path("max").canConvertToInt())
            throw new NormalizationFailure(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
        var claim = service.claim(body.path("max").asInt());
        return claim == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(claim);
    }

    @PutMapping("/results")
    public Submission submit(HttpServletRequest request) throws IOException {
        var body = body(request);
        UUID token;
        try {
            token = UUID.fromString(body.path("token").asString(""));
        } catch (IllegalArgumentException invalid) {
            throw new NormalizationFailure(HttpStatus.CONFLICT, "LEASE_LOST");
        }
        if (!body.path("results").isArray())
            throw new NormalizationFailure(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
        return service.submit(token, body.path("results").valueStream().toList());
    }

    @ExceptionHandler(NormalizationFailure.class)
    public ResponseEntity<ApiError> rejected(NormalizationFailure failure) {
        return ResponseEntity.status(failure.status())
                .body(
                        new ApiError(
                                failure.getMessage(),
                                "Normalization request rejected",
                                null,
                                List.of()));
    }

    @ExceptionHandler({
        org.springframework.dao.TransientDataAccessException.class,
        org.springframework.dao.DataAccessResourceFailureException.class
    })
    public ResponseEntity<ApiError> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        new ApiError(
                                "NORMALIZATION_UNAVAILABLE",
                                "Normalization unavailable",
                                null,
                                List.of()));
    }

    private JsonNode body(HttpServletRequest request) throws IOException {
        String type = request.getContentType(), encoding = request.getHeader("Content-Encoding");
        if (type == null
                || !type.toLowerCase(Locale.ROOT).matches("application/json(?:;\\s*charset=utf-8)?")
                || (encoding != null && !encoding.equalsIgnoreCase("identity")))
            throw new NormalizationFailure(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        int limit = properties.getMaxBodyBytes();
        if (request.getContentLengthLong() > limit)
            throw new NormalizationFailure(HttpStatus.CONTENT_TOO_LARGE, "RESULT_TOO_LARGE");
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit)
            throw new NormalizationFailure(HttpStatus.CONTENT_TOO_LARGE, "RESULT_TOO_LARGE");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            // Reject trailing documents as well as malformed JSON without exposing input in errors.
            var value =
                    json.reader()
                            .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .with(
                                    tools.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(text);
            if (value == null || !value.isObject())
                throw new NormalizationFailure(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
            return value;
        } catch (JacksonException | CharacterCodingException invalid) {
            throw new NormalizationFailure(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
        }
    }
}
