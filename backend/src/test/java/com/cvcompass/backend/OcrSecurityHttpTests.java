package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.config.*;
import com.cvcompass.backend.ingestion.dto.OcrWorkerPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real servlet security chain with test endpoints, no DB/engine integration claim. */
@WebMvcTest(controllers=OcrSecurityHttpTests.Endpoints.class)
@Import({OcrSecurityConfiguration.class,OcrSecurityHttpTests.Endpoints.class})
class OcrSecurityHttpTests {
    private static final String SECRET="s".repeat(43);
    @Autowired MockMvc mvc;
    @Autowired OcrIntegrationProperties properties;
    @BeforeEach void worker() {
        var worker=new OcrIntegrationProperties.Worker(); worker.setProfiles(Set.of("test-v1"));
        worker.setCredentialSha256(Set.of(OcrWorkerAuthenticationFilter.hash(SECRET)));
        properties.getWorkers().put("worker",worker);
    }
    @AfterEach void reset() { properties.setEnabled(false); SecurityContextHolder.clearContext(); }
    @Test void disabledInternalRoutesDenyBeforeMvc() throws Exception {
        mvc.perform(get("/internal/ocr/v1/security-test")).andExpect(status().isServiceUnavailable());
    }
    @Test void missingWrongAndBasicCredentialsDoNotAuthenticate() throws Exception {
        properties.setEnabled(true);
        for (String header:Set.of("Bearer "+"x".repeat(43),"Basic dGVzdDp0ZXN0"))
            mvc.perform(get("/internal/ocr/v1/security-test").header("Authorization",header)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/ocr/v1/security-test")).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedWorkerRoleIsStatelessAndPrincipalBound() throws Exception {
        properties.setEnabled(true);
        mvc.perform(post("/internal/ocr/v1/security-test").header("Authorization","Bearer "+SECRET))
                .andExpect(status().isOk()).andExpect(content().string("worker"))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/internal/ocr/v1/security-test")).andExpect(status().isUnauthorized());
    }
    @Test void unmatchedPublicRoutesPreserveExistingGetAndPostPolicy() throws Exception {
        mvc.perform(get("/api/security-public-test")).andExpect(status().isOk());
        mvc.perform(post("/api/security-public-test")).andExpect(status().isOk());
    }
    @RestController
    static class Endpoints {
        @RequestMapping("/internal/ocr/v1/security-test") String internal() {
            return ((OcrWorkerPrincipal)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).workerId();
        }
        @RequestMapping("/api/security-public-test") String external() { return "public"; }
    }
}
