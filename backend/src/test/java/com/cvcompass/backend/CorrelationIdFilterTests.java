package com.cvcompass.backend;

import com.cvcompass.backend.web.logging.CorrelationIdFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class CorrelationIdFilterTests {
    @Test
    void mdcIsRemovedEvenWhenDownstreamServletFails() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "servlet-failure");
        assertThatThrownBy(() -> new CorrelationIdFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("servlet-failure");
            throw new ServletException("synthetic failure");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("servlet-failure");
    }

    @Test
    void unrelatedMdcEntriesArePreserved() throws Exception {
        MDC.put("otherContext", "fixture");
        try {
            new CorrelationIdFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    (req, res) -> assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotBlank());
            assertThat(MDC.get("otherContext")).isEqualTo("fixture");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        } finally {
            MDC.remove("otherContext");
        }
    }
}
