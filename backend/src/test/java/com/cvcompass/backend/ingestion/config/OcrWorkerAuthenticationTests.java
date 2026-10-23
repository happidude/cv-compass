package com.cvcompass.backend.ingestion.config;

import com.cvcompass.backend.ingestion.dto.OcrWorkerPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class OcrWorkerAuthenticationTests {
    private final String secret="a".repeat(43);
    private final OcrIntegrationProperties properties=new OcrIntegrationProperties();
    private final OcrWorkerAuthenticationFilter filter=new OcrWorkerAuthenticationFilter(properties,new OcrWorkerAuthenticationProvider(properties));
    @org.junit.jupiter.api.AfterEach void clearContext() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("POST","/internal/ocr/v1/claims");
        request.setServletPath("/internal/ocr/v1/claims"); request.setRemoteAddr("127.0.0.1");
        return request;
    }
    private void enable() {
        properties.setEnabled(true);
        var worker=new OcrIntegrationProperties.Worker();
        worker.setCredentialSha256(Set.of(OcrWorkerAuthenticationFilter.hash(secret)));
        worker.setProfiles(Set.of("synthetic-v1")); properties.getWorkers().put("worker_1",worker);
    }
    @Test void disabledRejectsBeforeBodyOrChain() throws Exception {
        var response=new MockHttpServletResponse();
        filter.doFilter(request(),response,(req,res)-> { throw new AssertionError("chain invoked"); });
        assertThat(response.getStatus()).isEqualTo(503);
    }
    @Test void authenticationBindsServerOwnedPrincipal() throws Exception {
        enable(); var request=request(); request.addHeader("Authorization","Bearer "+secret);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->assertThat(req.getAttribute(OcrWorkerPrincipal.ATTRIBUTE))
                .isEqualTo(new OcrWorkerPrincipal("worker_1",Set.of("synthetic-v1"))));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void missingWrongAndDuplicateCredentialsAreRejectedWithoutEcho() throws Exception {
        enable();
        for (int mode=0;mode<3;mode++) {
            var request=request();
            if (mode==1) request.addHeader("Authorization","Bearer "+"b".repeat(43));
            if (mode==2) { request.addHeader("Authorization","Bearer "+secret); request.addHeader("Authorization","Bearer "+secret); }
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(req,res)-> { throw new AssertionError("chain invoked"); });
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).doesNotContain(secret);
        }
    }
    @Test void cleartextRemoteIsRejectedButTlsIsAllowed() throws Exception {
        enable(); var request=request(); request.setRemoteAddr("192.0.2.1");
        request.addHeader("Authorization","Bearer "+secret);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)-> { throw new AssertionError("chain invoked"); });
        assertThat(response.getStatus()).isEqualTo(403);
        request.setSecure(true);
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertThat(req.getAttribute(OcrWorkerPrincipal.ATTRIBUTE)).isNotNull());
    }
    @Test void publicUploadIsUnaffected() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/documents"); request.setServletPath("/api/documents");
        boolean[] reached={false};
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->reached[0]=true);
        assertThat(reached[0]).isTrue();
    }
}
