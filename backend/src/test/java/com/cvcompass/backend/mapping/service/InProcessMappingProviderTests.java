package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InProcessMappingProviderTests {
    @Test
    void delegatesExactlyOnceWithTheOriginalClaimAndInput() {
        var claim =
                new MappingClaim(
                        1, 2, 3, "synthetic", "synthetic", 1, UUID.randomUUID(), Instant.MAX, null);
        var input = new Extraction(2, 4, "synthetic", "success", false, List.of());
        var result = mock(MappingOutcome.class);
        var calls = new AtomicInteger();
        var provider =
                new InProcessMappingProvider(
                        "synthetic",
                        (actualClaim, actualInput) -> {
                            calls.incrementAndGet();
                            assertThat(actualClaim).isSameAs(claim);
                            assertThat(actualInput).isSameAs(input);
                            return result;
                        });

        assertThat(provider.map(claim, input)).isSameAs(result);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(provider.engineVersion()).isEqualTo("synthetic");
        assertThat(provider.kind()).isEqualTo(MappingProviderKind.IN_PROCESS);
    }
}
