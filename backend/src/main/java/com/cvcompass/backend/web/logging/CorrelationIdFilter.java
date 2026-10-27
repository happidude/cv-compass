package com.cvcompass.backend.web.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    private static final int MAX_LENGTH = 64;
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = request.getHeaders(HEADER_NAME);
        String incoming = headers.hasMoreElements() ? headers.nextElement() : null;
        boolean singleHeader = !headers.hasMoreElements();
        String id =
                singleHeader
                                && incoming != null
                                && incoming.length() <= MAX_LENGTH
                                && VALID_ID.matcher(incoming).matches()
                        ? incoming
                        : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, id);
        response.setHeader(HEADER_NAME, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
