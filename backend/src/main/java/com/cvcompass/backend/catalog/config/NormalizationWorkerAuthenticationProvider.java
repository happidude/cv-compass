package com.cvcompass.backend.catalog.config;

import com.cvcompass.backend.catalog.dto.NormalizationWorkerPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/** Per-worker credential verification; registration also prevents a generated default login user. */
public class NormalizationWorkerAuthenticationProvider implements AuthenticationProvider {
    private final NormalizationProperties properties;

    public NormalizationWorkerAuthenticationProvider(NormalizationProperties properties) {
        this.properties = properties;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = authentication.getCredentials() instanceof String value ? value : "";
        if (!properties.isEnabled() || !token.matches("[A-Za-z0-9_-]{43,256}"))
            throw new BadCredentialsException("UNAUTHORIZED");
        String hash = NormalizationWorkerAuthenticationFilter.hash(token);
        NormalizationWorkerPrincipal principal = null;
        for (var entry : properties.getWorkers().entrySet())
            for (String approved : entry.getValue().getCredentialSha256()) {
                if (MessageDigest.isEqual(
                        hash.getBytes(StandardCharsets.US_ASCII),
                        approved.getBytes(StandardCharsets.US_ASCII)))
                    principal = new NormalizationWorkerPrincipal(entry.getKey());
            }
        if (principal == null) throw new BadCredentialsException("UNAUTHORIZED");
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_NORMALIZATION_WORKER"));
    }

    @Override
    public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }
}
