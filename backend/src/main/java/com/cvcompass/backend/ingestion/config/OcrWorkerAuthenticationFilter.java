package com.cvcompass.backend.ingestion.config;

import com.cvcompass.backend.ingestion.dto.OcrWorkerPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before MVC/body decoding, including when integration is disabled. No credentials in logs. */
public class OcrWorkerAuthenticationFilter extends OncePerRequestFilter {
    private final OcrIntegrationProperties properties;
    private final OcrWorkerAuthenticationProvider provider;

    public OcrWorkerAuthenticationFilter(
            OcrIntegrationProperties properties, OcrWorkerAuthenticationProvider provider) {
        this.properties = properties;
        this.provider = provider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/internal/ocr") || path.startsWith("/internal/ocr/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!properties.isEnabled()) {
            reject(response, 503, "OCR_UNAVAILABLE");
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
                                    "ocr", header.substring(7)));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setAttribute(OcrWorkerPrincipal.ATTRIBUTE, authentication.getPrincipal());
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
                                + "\",\"message\":\"OCR access unavailable\",\"correlationId\":null,\"details\":[]}");
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
