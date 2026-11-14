package com.cvcompass.backend.mapping.config;

import com.cvcompass.backend.mapping.dto.MappingWorkerPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/** Per-worker credential verification; registration also prevents a generated default login user. */
public class MappingWorkerAuthenticationProvider implements AuthenticationProvider {
    private final MappingRemoteProperties properties;

    public MappingWorkerAuthenticationProvider(MappingRemoteProperties properties) {
        this.properties = properties;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = authentication.getCredentials() instanceof String value ? value : "";
        if (!properties.isEnabled() || !token.matches("[A-Za-z0-9_-]{43,256}"))
            throw new BadCredentialsException("UNAUTHORIZED");
        String hash = MappingWorkerAuthenticationFilter.hash(token);
        MappingWorkerPrincipal principal = null;
        for (var entry : properties.getWorkers().entrySet())
            for (String approved : entry.getValue().getCredentialSha256()) {
                if (MessageDigest.isEqual(
                        hash.getBytes(StandardCharsets.US_ASCII),
                        approved.getBytes(StandardCharsets.US_ASCII)))
                    principal = new MappingWorkerPrincipal(entry.getKey());
            }
        if (principal == null) throw new BadCredentialsException("UNAUTHORIZED");
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_MAPPING_WORKER"));
    }

    @Override
    public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }
}
