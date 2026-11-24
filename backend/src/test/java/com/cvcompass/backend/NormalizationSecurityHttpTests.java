package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cvcompass.backend.catalog.config.NormalizationConfiguration;
import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.config.NormalizationSecurityConfiguration;
import com.cvcompass.backend.catalog.config.NormalizationWorkerAuthenticationFilter;
import com.cvcompass.backend.catalog.dto.NormalizationWorkerPrincipal;
import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.config.MappingRemoteSecurityConfiguration;
import com.cvcompass.backend.mapping.config.MappingWorkerAuthenticationFilter;
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

@WebMvcTest(controllers = NormalizationSecurityHttpTests.Endpoints.class)
@ExtendWith(OutputCaptureExtension.class)
@Import({
    NormalizationConfiguration.class,
    NormalizationSecurityConfiguration.class,
    MappingRemoteSecurityConfiguration.class,
    NormalizationSecurityHttpTests.Endpoints.class
})
class NormalizationSecurityHttpTests {
    private static final String TOKEN = "synthetic-normalization-" + "n".repeat(43);
    private static final String MAPPING_TOKEN = "synthetic-mapping-" + "m".repeat(43);
    @Autowired MockMvc mvc;
    @Autowired NormalizationProperties normalization;
    @Autowired MappingRemoteProperties mapping;

    @BeforeEach
    void configure() {
        var worker = new NormalizationProperties.Worker();
        worker.setCredentialSha256(Set.of(NormalizationWorkerAuthenticationFilter.hash(TOKEN)));
        normalization.setWorkers(Map.of("synthetic-worker", worker));
        normalization.setEnabled(true);
        var other = new MappingRemoteProperties.Worker();
        other.setCredentialSha256(Set.of(MappingWorkerAuthenticationFilter.hash(MAPPING_TOKEN)));
        mapping.setWorkers(Map.of("synthetic-mapping", other));
        mapping.setEnabled(true);
        SecurityContextHolder.clearContext();
    }

    @Test
    void workerCredentialsAreIsolatedAndNeverLogged(CapturedOutput output) throws Exception {
        mvc.perform(get("/internal/normalization/v1/security-test"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/internal/normalization/unknown")
                                .header("Authorization", "Bearer " + MAPPING_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/internal/mapping/v1/security-test")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/internal/normalization/v1/security-test")
                                .header("Authorization", "Bearer " + TOKEN, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        assertThat(output.getAll()).doesNotContain(TOKEN, MAPPING_TOKEN);
    }

    @Test
    void disabledRoutesReturn503EvenWithoutCredentials() throws Exception {
        normalization.setEnabled(false);
        mvc.perform(get("/internal/normalization/unknown"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void onlyTlsOrLoopbackMayUseTheWorkerRole() throws Exception {
        mvc.perform(
                        post("/internal/normalization/v1/security-test")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("192.0.2.1");
                                            return request;
                                        })
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/internal/normalization/v1/security-test")
                                .secure(true)
                                .with(
                                        request -> {
                                            request.setRemoteAddr("192.0.2.1");
                                            return request;
                                        })
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("synthetic-worker"));
        mvc.perform(
                        post("/internal/normalization/v1/security-test")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @RestController
    static class Endpoints {
        @RequestMapping("/internal/normalization/v1/security-test")
        String normalization() {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getAuthorities())
                    .extracting(a -> a.getAuthority())
                    .containsExactly("ROLE_NORMALIZATION_WORKER");
            return ((NormalizationWorkerPrincipal) authentication.getPrincipal()).workerId();
        }

        @RequestMapping("/internal/mapping/v1/security-test")
        String mapping() {
            return "synthetic-mapping";
        }
    }
}
