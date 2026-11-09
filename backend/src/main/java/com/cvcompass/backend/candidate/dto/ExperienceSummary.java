package com.cvcompass.backend.candidate.dto;

import java.util.List;
import java.util.UUID;

public record ExperienceSummary(
        long totalMonths,
        List<UUID> countedIds,
        List<Skipped> skipped,
        List<String> ongoingReferenceDates,
        boolean hasYearOnlyPeriods) {

    public ExperienceSummary {
        countedIds = List.copyOf(countedIds);
        skipped = List.copyOf(skipped);
        ongoingReferenceDates = List.copyOf(ongoingReferenceDates);
    }

    public record Skipped(UUID id, SkipReason reason) {}

    public enum SkipReason {
        MISSING_START,
        MISSING_END,
        PERIOD_UNRESOLVED,
        EXCLUDED_BY_USER,
        NO_REFERENCE_DATE,
        START_AFTER_REFERENCE
    }
}
