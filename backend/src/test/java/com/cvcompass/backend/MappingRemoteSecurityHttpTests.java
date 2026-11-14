package com.cvcompass.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cvcompass.backend.ingestion.config.OcrIntegrationProperties;
import com.cvcompass.backend.ingestion.config.OcrSecurityConfiguration;
import com.cvcompass.backend.ingestion.config.OcrWorkerAuthenticationFilter;
import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.config.MappingRemoteSecurityConfiguration;
import com.cvcompass.backend.mapping.config.MappingWorkerAuthenticationFilter;
import com.cvcompass.backend.mapping.dto.MappingWorkerPrincipal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = MappingRemoteSecurityHttpTests.Endpoints.class)
@ExtendWith(OutputCaptureExtension.class)
@Import({
    MappingRemoteSecurityConfiguration.class,
    OcrSecurityConfiguration.class,
    MappingRemoteSecurityHttpTests.Endpoints.class
})
class MappingRemoteSecurityHttpTests {
    private static final String MAPPING_TOKEN = "synthetic-mapping-token-" + "m".repeat(43);
    private static final String OCR_TOKEN = "synthetic-ocr-token-" + "o".repeat(43);
    @Autowired MockMvc mvc;
    @Autowired MappingRemoteProperties mapping;
    @Autowired OcrIntegrationProperties ocr;

    @BeforeEach
    void setup() {
        var worker = new MappingRemoteProperties.Worker();
        worker.setCredentialSha256(Set.of(MappingWorkerAuthenticationFilter.hash(MAPPING_TOKEN)));
        mapping.setWorkers(Map.of("synthetic-worker", worker));
        mapping.setEnabled(true);
        var ocrWorker = new OcrIntegrationProperties.Worker();
        ocrWorker.setCredentialSha256(Set.of(OcrWorkerAuthenticationFilter.hash(OCR_TOKEN)));
        ocrWorker.setProfiles(Set.of("synthetic-profile"));
        ocr.getWorkers().put("synthetic-ocr", ocrWorker);
        ocr.setEnabled(true);
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAndCrossModuleCredentialsAreRejectedIncludingUnknownRoute(CapturedOutput output)
            throws Exception {
        mvc.perform(get("/internal/mapping/v1/security-test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/mapping/does-not-exist")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/internal/mapping/v1/security-test")
                                .header("Authorization", "Bearer " + OCR_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/internal/ocr/v1/security-test")
                                .header("Authorization", "Bearer " + MAPPING_TOKEN))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(output.getAll())
                .doesNotContain(MAPPING_TOKEN, OCR_TOKEN);
    }

    @Test
    void disabledRoutesAlwaysReturn503() throws Exception {
        mapping.setEnabled(false);
        mvc.perform(
                        get("/internal/mapping/v1/security-test")
                                .header("Authorization", "Bearer " + MAPPING_TOKEN))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/internal/mapping/does-not-exist"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void remotePlaintextIsForbiddenWhileTlsAndLoopbackAreAllowed() throws Exception {
        mvc.perform(
                        get("/internal/mapping/v1/security-test")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("192.0.2.1");
                                            return request;
                                        })
                                .header("Authorization", "Bearer " + MAPPING_TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/internal/mapping/v1/security-test")
                                .secure(true)
                                .with(
                                        request -> {
                                            request.setRemoteAddr("192.0.2.1");
                                            return request;
                                        })
                                .header("Authorization", "Bearer " + MAPPING_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("synthetic-worker"));
        mvc.perform(
                        post("/internal/mapping/v1/security-test")
                                .header("Authorization", "Bearer " + MAPPING_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @RestController
    static class Endpoints {
        @RequestMapping("/internal/mapping/v1/security-test")
        String mapping() {
            return ((MappingWorkerPrincipal)
                            SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .workerId();
        }

        @RequestMapping("/internal/ocr/v1/security-test")
        String ocr() {
            return "synthetic-ocr";
        }
    }
}
