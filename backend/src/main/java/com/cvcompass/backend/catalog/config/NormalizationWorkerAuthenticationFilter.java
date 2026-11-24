package com.cvcompass.backend.catalog.config;

import com.cvcompass.backend.catalog.dto.NormalizationWorkerPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local authentication keeps worker credentials isolated from other internal APIs. */
public class NormalizationWorkerAuthenticationFilter extends OncePerRequestFilter {
    private final NormalizationProperties properties;
    private final NormalizationWorkerAuthenticationProvider provider;

    public NormalizationWorkerAuthenticationFilter(
            NormalizationProperties properties,
            NormalizationWorkerAuthenticationProvider provider) {
        this.properties = properties;
        this.provider = provider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/internal/normalization")
                || path.startsWith("/internal/normalization/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!properties.isEnabled()) {
            reject(response, 503, "NORMALIZATION_UNAVAILABLE");
            return;
        }
        String remote = request.getRemoteAddr();
        if (!request.isSecure()
                && !("127.0.0.1".equals(remote)
                        || "::1".equals(remote)
                        || "0:0:0:0:0:0:0:1".equals(remote))) {
            reject(response, 403, "TLS_REQUIRED");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null
                || !header.matches("Bearer [A-Za-z0-9_-]{43,256}")
                || java.util.Collections.list(request.getHeaders("Authorization")).size() != 1) {
            reject(response, 401, "UNAUTHORIZED");
            return;
        }
        try {
            var authentication =
                    provider.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    "normalization", header.substring(7)));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setAttribute(
                    NormalizationWorkerPrincipal.ATTRIBUTE, authentication.getPrincipal());
        } catch (AuthenticationException rejected) {
            reject(response, 401, "UNAUTHORIZED");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        "{\"code\":\""
                                + code
                                + "\",\"message\":\"Normalization access unavailable\",\"correlationId\":null,\"details\":[]}");
    }

    public static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
