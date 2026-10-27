package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.mapping.dto.MappingClaim;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class MappingWorkerTests {
    @Test
    void onlyKnownTransientFailuresAreRetried() {
        for (boolean transientFailure : new boolean[] {false, true}) {
            var mapping = mock(MappingService.class);
            var claim =
                    new MappingClaim(
                            1,
                            2,
                            3,
                            "synthetic",
                            MappingEngine.VERSION,
                            1,
                            java.util.UUID.randomUUID(),
                            java.time.Instant.MAX,
                            null);
            when(mapping.claim()).thenReturn(Optional.of(claim));
            when(mapping.process(claim))
                    .thenThrow(
                            transientFailure
                                    ? new TransientDataAccessResourceException(
                                            "synthetic temporary")
                                    : new IllegalArgumentException(
                                            "synthetic invalid configuration"));
            assertThat(new MappingWorker(mapping).runOne()).isTrue();
            verify(mapping).fail(claim, transientFailure);
        }
    }
}
