package com.cvcompass.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.catalog.api.CatalogLookup;
import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.repository.NormalizationCatalogRepository;
import com.cvcompass.backend.catalog.repository.NormalizationTaskRepository;
import com.cvcompass.backend.catalog.service.NormalizationLinkService.Alias;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class NormalizationLinkServiceTests {
    @Test
    void submitScanUsesTheConfiguredPageSize() {
        var candidates = mock(CatalogLinks.class);
        var properties = new NormalizationProperties();
        properties.setCandidateScanPageSize(2);
        var service =
                new NormalizationLinkService(
                        candidates,
                        mock(CatalogLookup.class),
                        mock(NormalizationCatalogRepository.class),
                        mock(NormalizationTaskRepository.class),
                        mock(ApplicationEventPublisher.class),
                        new ObjectMapper(),
                        properties);
        when(candidates.lastCandidateId()).thenReturn(3L);
        when(candidates.indexCandidates(0, 3, 2)).thenReturn(List.of(1L, 2L));
        when(candidates.indexCandidates(2, 3, 2)).thenReturn(List.of(3L));
        assertThat(service.linkMatches(Map.of(new Alias("COMPANY", "synthetic alias"), 10L)))
                .isEmpty();
        verify(candidates).indexCandidates(0, 3, 2);
        verify(candidates).indexCandidates(2, 3, 2);
        for (long id = 1; id <= 3; id++) {
            verify(candidates).experiences(id);
            verify(candidates, never()).lockIndexProfile(id);
        }
    }

    @Test
    void scansEachPageAndReadsEachCandidateOnceForMultipleAliasesLockingOnlyMatches() {
        var candidates = mock(CatalogLinks.class);
        var lookup = mock(CatalogLookup.class);
        var service =
                new NormalizationLinkService(
                        candidates,
                        lookup,
                        mock(NormalizationCatalogRepository.class),
                        mock(NormalizationTaskRepository.class),
                        mock(ApplicationEventPublisher.class),
                        new ObjectMapper(),
                        new NormalizationProperties());
        when(candidates.lastCandidateId()).thenReturn(101L);
        when(candidates.indexCandidates(0, 101, 100))
                .thenReturn(LongStream.rangeClosed(1, 100).boxed().toList());
        when(candidates.indexCandidates(100, 101, 100)).thenReturn(List.of(101L));
        when(lookup.companies(List.of(10L)))
                .thenReturn(
                        Map.of(
                                10L,
                                new CatalogLookup.Company(
                                        10, "Synthetic canonical", null, null, "RETAIL", "AI")));
        when(lookup.titles(List.of(20L)))
                .thenReturn(
                        Map.of(
                                20L,
                                new CatalogLookup.Title(
                                        20,
                                        "Synthetic title",
                                        List.of("IT"),
                                        "STAFF",
                                        null,
                                        "AI")));
        for (long id = 1; id <= 101; id++) {
            when(candidates.experiences(id))
                    .thenReturn(List.of(experience(id, id == 1 || id == 101)));
        }
        for (long id : List.of(1L, 101L)) {
            when(candidates.lockIndexProfile(id))
                    .thenReturn(Optional.of(new CatalogLinks.IndexProfile(null, null, null)));
            when(candidates.normalize(eq(id), any(), any())).thenReturn(true);
        }
        var affected =
                service.linkMatches(
                        Map.of(
                                new Alias("COMPANY", "synthetic alias"),
                                10L,
                                new Alias("TITLE", "synthetic title"),
                                20L));
        assertThat(affected).containsExactly(1L, 101L);
        verify(candidates, times(1)).indexCandidates(0, 101, 100);
        verify(candidates, times(1)).indexCandidates(100, 101, 100);
        for (long id = 1; id <= 101; id++) {
            verify(candidates, times(1)).experiences(id);
            if (id != 1 && id != 101) verify(candidates, never()).lockIndexProfile(id);
        }
        for (long id : affected) {
            verify(candidates, times(1)).lockIndexProfile(id);
            var normalization =
                    org.mockito.ArgumentCaptor.forClass(CatalogLinks.Normalization.class);
            verify(candidates, times(1)).normalize(eq(id), any(), normalization.capture());
            assertThat(normalization.getValue().companyId()).isEqualTo(10L);
            assertThat(normalization.getValue().titleId()).isEqualTo(20L);
        }
    }

    private CatalogLinks.ExperienceLink experience(long id, boolean matching) {
        return new CatalogLinks.ExperienceLink(
                UUID.randomUUID(),
                id,
                null,
                null,
                null,
                null,
                matching ? "Synthetic alias" : "Synthetic unrelated",
                null,
                matching ? "Synthetic title" : null,
                "[]",
                "[]",
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                "{}");
    }
}
