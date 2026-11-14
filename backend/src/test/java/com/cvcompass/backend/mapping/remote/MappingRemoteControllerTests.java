package com.cvcompass.backend.mapping.remote;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.dto.MappingWorkerPrincipal;
import com.cvcompass.backend.mapping.service.MappingRemoteService;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MappingRemoteControllerTests {
    private final MappingRemoteService service = mock(MappingRemoteService.class);
    private final MappingRemoteProperties properties = new MappingRemoteProperties();
    private MockMvc mvc;
    private static final String TOKEN = "synthetic-invalid-lease";

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new MappingRemoteController(service, properties))
                        .build();
    }

    @Test
    void contentLengthAndChunkedBodiesAreBoundedBeforeServiceOrParsing() throws Exception {
        properties.setMaxResultBytes(32);
        for (boolean chunked : new boolean[] {false, true}) {
            var request =
                    put("/internal/mapping/v1/attempts/" + UUID.randomUUID() + "/result")
                            .requestAttr(
                                    MappingWorkerPrincipal.ATTRIBUTE,
                                    new MappingWorkerPrincipal("synthetic-worker"))
                            .header("X-Lease-Token", "s".repeat(43))
                            .contentType("application/json")
                            .content("x".repeat(33));
            if (chunked)
                request.with(
                        original ->
                                new org.springframework.mock.web.MockHttpServletRequest() {
                                    {
                                        setMethod(original.getMethod());
                                        setRequestURI(original.getRequestURI());
                                        setContentType("application/json");
                                        addHeader("X-Lease-Token", "s".repeat(43));
                                        setAttribute(
                                                MappingWorkerPrincipal.ATTRIBUTE,
                                                new MappingWorkerPrincipal("synthetic-worker"));
                                    }

                                    @Override
                                    public int getContentLength() {
                                        return -1;
                                    }

                                    @Override
                                    public long getContentLengthLong() {
                                        return -1;
                                    }

                                    @Override
                                    public jakarta.servlet.ServletInputStream getInputStream() {
                                        return new DelegatingServletInputStream(
                                                new ByteArrayInputStream(
                                                        "x"
                                                                .repeat(33)
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8)));
                                    }
                                });
            mvc.perform(request)
                    .andExpect(status().is(413))
                    .andExpect(jsonPath("$.code").value("RESULT_TOO_LARGE"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidLeaseSyntaxIsRejectedBeforeService() throws Exception {
        mvc.perform(
                        put("/internal/mapping/v1/attempts/" + UUID.randomUUID() + "/result")
                                .requestAttr(
                                        MappingWorkerPrincipal.ATTRIBUTE,
                                        new MappingWorkerPrincipal("synthetic-worker"))
                                .header("X-Lease-Token", TOKEN)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("INVALID_LEASE_TOKEN"));
        verifyNoInteractions(service);
    }

    @Test
    void missingAndDuplicateLeaseHeadersUseTheSameConflictCode() throws Exception {
        for (boolean duplicate : new boolean[] {false, true}) {
            var request =
                    put("/internal/mapping/v1/attempts/" + UUID.randomUUID() + "/result")
                            .requestAttr(
                                    MappingWorkerPrincipal.ATTRIBUTE,
                                    new MappingWorkerPrincipal("synthetic-worker"))
                            .contentType("application/json")
                            .content("{}");
            if (duplicate) request.header("X-Lease-Token", "s".repeat(43), "t".repeat(43));
            mvc.perform(request)
                    .andExpect(status().is(409))
                    .andExpect(jsonPath("$.code").value("INVALID_LEASE_TOKEN"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void missingDuplicateAndMalformedIdempotencyHeadersAreProtocolErrors() throws Exception {
        for (String mode : java.util.List.of("missing", "duplicate", "malformed", "noncanonical")) {
            var request =
                    post("/internal/mapping/v1/claims")
                            .requestAttr(
                                    MappingWorkerPrincipal.ATTRIBUTE,
                                    new MappingWorkerPrincipal("synthetic-worker"))
                            .header("X-Lease-Token", "s".repeat(43))
                            .contentType("application/json")
                            .content("{}");
            switch (mode) {
                case "duplicate" ->
                        request.header(
                                "Idempotency-Key",
                                UUID.randomUUID().toString(),
                                UUID.randomUUID().toString());
                case "malformed" -> request.header("Idempotency-Key", "synthetic-invalid-uuid");
                case "noncanonical" -> request.header("Idempotency-Key", "1-1-1-1-1");
            }
            mvc.perform(request)
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_PROTOCOL"));
        }
        verifyNoInteractions(service);
    }
}
