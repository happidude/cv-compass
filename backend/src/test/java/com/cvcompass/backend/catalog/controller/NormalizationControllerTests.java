package com.cvcompass.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.exception.NormalizationFailure;
import com.cvcompass.backend.catalog.service.NormalizationService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class NormalizationControllerTests {
    private final NormalizationService service = mock(NormalizationService.class);
    private final NormalizationProperties properties = new NormalizationProperties();
    private final NormalizationController controller =
            new NormalizationController(service, properties, new ObjectMapper());

    @Test
    void streamingLimitAppliesWhenContentLengthIsUnknownOrUnderreported() {
        properties.setMaxBodyBytes(16);
        for (long length : new long[] {-1, 2}) {
            var request = request(" ".repeat(17).getBytes(StandardCharsets.UTF_8), length);
            assertThatThrownBy(() -> controller.claim(request))
                    .isInstanceOfSatisfying(
                            NormalizationFailure.class,
                            failure -> {
                                assertThat(failure.status()).isEqualTo(413);
                                assertThat(failure.getMessage()).isEqualTo("RESULT_TOO_LARGE");
                            });
        }
        verifyNoInteractions(service);
    }

    @Test
    void malformedUtf8AndDuplicateFieldsAreRejectedWithoutCallingService() {
        for (byte[] body :
                new byte[][] {
                    new byte[] {(byte) 0xc3, (byte) 0x28},
                    "{\"max\":1,\"max\":2}".getBytes(StandardCharsets.UTF_8)
                }) {
            assertThatThrownBy(() -> controller.claim(request(body, -1)))
                    .isInstanceOfSatisfying(
                            NormalizationFailure.class,
                            failure -> assertThat(failure.status()).isEqualTo(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void compressedRequestIsRejectedBeforeReadingBody() {
        var request = request(new byte[0], -1);
        request.addHeader("Content-Encoding", "gzip");
        assertThatThrownBy(() -> controller.claim(request))
                .isInstanceOfSatisfying(
                        NormalizationFailure.class,
                        failure -> assertThat(failure.status()).isEqualTo(415));
        verifyNoInteractions(service);
    }

    private MockHttpServletRequest request(byte[] body, long length) {
        var request =
                new MockHttpServletRequest() {
                    @Override
                    public long getContentLengthLong() {
                        return length;
                    }
                };
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
