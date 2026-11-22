package com.cvcompass.backend.candidate.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.candidate.metrics.ExperienceMonths.Period;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExperienceMonthsTests {
    @Test
    void currentPrefersLatestOngoingStartEvenWhenItHasNoLinks() {
        var old = new Period(LocalDate.of(2024, 1, 1), "MONTH", null, null, true, 1L, 2L);
        var newest = period("2025-01-01", "MONTH", null, null, true);
        var ended = period("2025-02-01", "MONTH", "2026-10-01", "MONTH", false);
        assertThat(ExperienceMonths.current(List.of(ended, newest, old))).contains(newest);
        assertThat(ExperienceMonths.current(List.of(ended, newest, old)).orElseThrow().companyId())
                .isNull();
    }

    @Test
    void currentFallsBackToLatestEndAndRetainsLinkedIds() {
        var latest =
                new Period(
                        LocalDate.of(2024, 1, 1),
                        "MONTH",
                        LocalDate.of(2026, 8, 1),
                        "MONTH",
                        false,
                        3L,
                        4L);
        var earlier = period("2025-01-01", "MONTH", "2026-07-01", "MONTH", false);
        assertThat(ExperienceMonths.current(List.of(latest, earlier))).contains(latest);
        assertThat(ExperienceMonths.current(List.of())).isEmpty();
        var laterDay = period("2024-01-01", "DAY", "2026-08-02", "DAY", false);
        assertThat(ExperienceMonths.current(List.of(latest, laterDay))).contains(laterDay);
    }

    private static Period period(
            String start, String startPrecision, String end, String endPrecision, boolean ongoing) {
        return new Period(
                start == null ? null : LocalDate.parse(start),
                startPrecision,
                end == null ? null : LocalDate.parse(end),
                endPrecision,
                ongoing,
                null,
                null);
    }
}
