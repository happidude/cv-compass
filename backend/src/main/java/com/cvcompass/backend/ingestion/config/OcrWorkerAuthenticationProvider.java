package com.cvcompass.backend.ingestion.config;

import com.cvcompass.backend.ingestion.dto.OcrWorkerPrincipal;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.AuthorityUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Per-worker credential verification; registration also prevents a generated default login user. */
public class OcrWorkerAuthenticationProvider implements AuthenticationProvider {
    private final OcrIntegrationProperties properties;
    public OcrWorkerAuthenticationProvider(OcrIntegrationProperties properties) { this.properties=properties; }
    @Override public Authentication authenticate(Authentication authentication) {
        String token=authentication.getCredentials() instanceof String value ? value : "";
        if (!properties.isEnabled() || !token.matches("[A-Za-z0-9_-]{43,256}")) throw new BadCredentialsException("UNAUTHORIZED");
        String hash=OcrWorkerAuthenticationFilter.hash(token);
        OcrWorkerPrincipal principal=null;
        for (var entry:properties.getWorkers().entrySet()) for (String approved:entry.getValue().getCredentialSha256()) {
            if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),approved.getBytes(StandardCharsets.US_ASCII)))
                principal=new OcrWorkerPrincipal(entry.getKey(),entry.getValue().getProfiles());
        }
        if (principal==null) throw new BadCredentialsException("UNAUTHORIZED");
        return UsernamePasswordAuthenticationToken.authenticated(principal,null,AuthorityUtils.createAuthorityList("ROLE_OCR_WORKER"));
    }
    @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
