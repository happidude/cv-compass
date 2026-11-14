package com.cvcompass.backend.mapping.remote;

import static com.cvcompass.backend.mapping.remote.MappingRemoteException.require;

import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.config.MappingWorkerAuthenticationFilter;
import com.cvcompass.backend.mapping.dto.MappingWorkerPrincipal;
import com.cvcompass.backend.mapping.service.MappingRemoteService;
import com.cvcompass.backend.web.ApiError;
import com.cvcompass.backend.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/mapping/v1")
public class MappingRemoteController {
    private final MappingRemoteService service;
    private final MappingRemoteProperties properties;

    public MappingRemoteController(
            MappingRemoteService service, MappingRemoteProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/claims")
    public ResponseEntity<ApiResponse<Map<String, Object>>> claim(HttpServletRequest request)
            throws IOException {
        var reply =
                service.claim(
                        worker(request),
                        uuid(single(request, "Idempotency-Key")),
                        tokenHash(request),
                        body(request, properties.getControlBodyBytes()));
        if (reply.status() == 204) return ResponseEntity.noContent().build();
        return ResponseEntity.status(reply.status())
                .header(
                        "Location",
                        "/internal/mapping/v1/attempts/" + reply.data().get("attemptId"))
                .body(new ApiResponse<>(reply.data()));
    }

    @GetMapping("/attempts/{id}/input")
    public ApiResponse<Map<String, Object>> input(
            @PathVariable UUID id, HttpServletRequest request) {
        return new ApiResponse<>(service.input(worker(request), id, tokenHash(request)));
    }

    @PutMapping("/attempts/{id}/lease")
    public ApiResponse<Map<String, Object>> lease(@PathVariable UUID id, HttpServletRequest request)
            throws IOException {
        return new ApiResponse<>(
                service.heartbeat(
                        worker(request),
                        id,
                        tokenHash(request),
                        body(request, properties.getControlBodyBytes())));
    }

    @PutMapping("/attempts/{id}/result")
    public ApiResponse<Map<String, Object>> result(
            @PathVariable UUID id, HttpServletRequest request) throws IOException {
        return new ApiResponse<>(
                service.result(
                        worker(request),
                        id,
                        tokenHash(request),
                        body(request, properties.getMaxResultBytes())));
    }

    @PutMapping("/attempts/{id}/failure")
    public ApiResponse<Map<String, Object>> failure(
            @PathVariable UUID id, HttpServletRequest request) throws IOException {
        return new ApiResponse<>(
                service.failure(
                        worker(request),
                        id,
                        tokenHash(request),
                        body(request, properties.getControlBodyBytes())));
    }

    @ExceptionHandler(MappingRemoteException.class)
    public ResponseEntity<ApiError> rejected(
            MappingRemoteException failure, HttpServletResponse response) {
        return ResponseEntity.status(failure.status())
                .body(
                        new ApiError(
                                failure.getMessage(),
                                "Mapping request rejected",
                                response.getHeader("X-Correlation-ID"),
                                List.of()));
    }

    @ExceptionHandler({
        org.springframework.dao.TransientDataAccessException.class,
        org.springframework.dao.DataAccessResourceFailureException.class
    })
    public ResponseEntity<ApiError> unavailable(HttpServletResponse response) {
        return ResponseEntity.status(503)
                .body(
                        new ApiError(
                                "MAPPING_REMOTE_UNAVAILABLE",
                                "Mapping unavailable",
                                response.getHeader("X-Correlation-ID"),
                                List.of()));
    }

    private static String worker(HttpServletRequest request) {
        Object principal = request.getAttribute(MappingWorkerPrincipal.ATTRIBUTE);
        require(principal instanceof MappingWorkerPrincipal, 401, "UNAUTHORIZED");
        return ((MappingWorkerPrincipal) principal).workerId();
    }

    private static String tokenHash(HttpServletRequest request) {
        String token = single(request, "X-Lease-Token");
        require(token != null && token.matches("[A-Za-z0-9_-]{43}"), 409, "INVALID_LEASE_TOKEN");
        return MappingWorkerAuthenticationFilter.hash(token);
    }

    private static String single(HttpServletRequest request, String name) {
        boolean lease = "X-Lease-Token".equals(name);
        require(
                Collections.list(request.getHeaders(name)).size() == 1,
                lease ? 409 : 422,
                lease ? "INVALID_LEASE_TOKEN" : "UNSUPPORTED_PROTOCOL");
        return request.getHeader(name);
    }

    private static UUID uuid(String raw) {
        try {
            UUID value = UUID.fromString(raw);
            require(value.toString().equals(raw), 422, "UNSUPPORTED_PROTOCOL");
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new MappingRemoteException(422, "UNSUPPORTED_PROTOCOL");
        }
    }

    private static String body(HttpServletRequest request, int limit) throws IOException {
        String type = request.getContentType(), encoding = request.getHeader("Content-Encoding");
        require(
                type != null
                        && type.toLowerCase(Locale.ROOT)
                                .matches("application/json(?:;\\s*charset=utf-8)?")
                        && (encoding == null || encoding.equalsIgnoreCase("identity")),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
        require(request.getContentLengthLong() <= limit, 413, "RESULT_TOO_LARGE");
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        require(bytes.length <= limit, 413, "RESULT_TOO_LARGE");
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new MappingRemoteException(422, "INVALID_RESULT");
        }
    }
}
