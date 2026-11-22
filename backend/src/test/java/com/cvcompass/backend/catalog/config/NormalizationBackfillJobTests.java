package com.cvcompass.backend.catalog.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.catalog.service.NormalizationLinkService;
import org.junit.jupiter.api.Test;

class NormalizationBackfillJobTests {
    @Test
    void scansStableUpperBoundInConfiguredBatches() {
        var links = mock(NormalizationLinkService.class);
        var properties = new NormalizationProperties();
        properties.setBatchSize(2);
        when(links.lastCandidateId()).thenReturn(5L);
        when(links.backfillBatch(0, 5, 2)).thenReturn(3L);
        when(links.backfillBatch(3, 5, 2)).thenReturn(5L);
        new NormalizationBackfillJob(links, properties).backfill();
        verify(links).backfillBatch(0, 5, 2);
        verify(links).backfillBatch(3, 5, 2);
    }

    @Test
    void schedulerCannotLogSqlExceptionDetailsContainingAliases() {
        var links = mock(NormalizationLinkService.class);
        when(links.lastCandidateId())
                .thenThrow(new IllegalStateException("Synthetic alias in SQL detail"));
        assertThatThrownBy(
                        () ->
                                new NormalizationBackfillJob(links, new NormalizationProperties())
                                        .backfill())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NORMALIZATION_BACKFILL_FAILED")
                .hasNoCause();
    }
}
