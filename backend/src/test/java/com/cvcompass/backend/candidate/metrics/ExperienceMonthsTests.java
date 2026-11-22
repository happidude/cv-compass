package com.cvcompass.backend.candidate.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.candidate.metrics.ExperienceMonths.Period;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExperienceMonthsTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    @Test
    void mergesOverlapsDuplicatesAndCountsBothBoundaryMonths() {
        var first = period("2024-01-01", "MONTH", "2024-03-01", "MONTH", false);
        var overlapping = period("2024-02-01", "MONTH", "2024-05-01", "MONTH", false);
        assertThat(ExperienceMonths.total(List.of(first, overlapping, first), TODAY)).isEqualTo(5);
        assertThat(ExperienceMonths.total(List.of(first), TODAY)).isEqualTo(3);
    }

    @Test
    void mergesAdjacentMonthsAndKeepsGaps() {
        var first = period("2024-01-01", "MONTH", "2024-03-01", "MONTH", false);
        var adjacent = period("2024-04-01", "MONTH", "2024-04-01", "MONTH", false);
        var separate = period("2024-06-01", "MONTH", "2024-07-01", "MONTH", false);
        assertThat(ExperienceMonths.total(List.of(separate, adjacent, first), TODAY)).isEqualTo(6);
    }

    @Test
    void yearPrecisionUsesDecemberToJanuaryAndSameYearCountsOnce() {
        assertThat(
                        ExperienceMonths.total(
                                List.of(period("2020-01-01", "YEAR", "2022-01-01", "YEAR", false)),
                                TODAY))
                .isEqualTo(14);
        assertThat(
                        ExperienceMonths.total(
                                List.of(period("2020-01-01", "YEAR", "2020-01-01", "YEAR", false)),
                                TODAY))
                .isEqualTo(1);
        assertThat(
                        ExperienceMonths.total(
                                List.of(period("2020-12-01", "YEAR", "2020-01-01", "YEAR", false)),
                                TODAY))
                .isEqualTo(1);
        assertThat(
                        ExperienceMonths.total(
                                List.of(period("2020-01-01", "YEAR", "2020-03-01", "MONTH", false)),
                                TODAY))
                .isZero();
        assertThat(
                        ExperienceMonths.total(
                                List.of(period("2020-12-01", "MONTH", "2021-01-01", "YEAR", false)),
                                TODAY))
                .isEqualTo(2);
    }

    @Test
    void ongoingUsesTheSuppliedTodayAndIgnoresStoredEnd() {
        var ongoing = period("2026-09-01", "MONTH", "2020-01-01", "YEAR", true);
        assertThat(ExperienceMonths.total(List.of(ongoing), TODAY)).isEqualTo(3);
        assertThat(ExperienceMonths.total(List.of(ongoing), TODAY.plusMonths(1))).isEqualTo(4);
    }

    @Test
    void skipsMissingDatesReversedAndFutureOngoingRanges() {
        assertThat(
                        ExperienceMonths.total(
                                List.of(
                                        period(null, null, "2024-01-01", "MONTH", false),
                                        period("2024-01-01", "MONTH", null, null, false),
                                        period("2025-01-01", "YEAR", "2024-01-01", "YEAR", false),
                                        period("2024-01-20", "DAY", "2024-01-10", "DAY", false),
                                        period("2027-01-01", "MONTH", null, null, true)),
                                TODAY))
                .isZero();
        assertThat(ExperienceMonths.total(List.of(), TODAY)).isZero();
    }

    @Test
    void skipsRowsExcludedByUserOrWithUnresolvedPeriodState() {
        var counted = period("2024-01-01", "MONTH", "2024-03-01", "MONTH", false);
        assertThat(ExperienceMonths.total(List.of(counted, excluded(false, null)), TODAY))
                .isEqualTo(3);
        assertThat(ExperienceMonths.total(List.of(counted, excluded(true, "INVALID")), TODAY))
                .isEqualTo(3);
        assertThat(ExperienceMonths.total(List.of(counted, excluded(true, "AMBIGUOUS")), TODAY))
                .isEqualTo(3);
        assertThat(ExperienceMonths.total(List.of(counted, excluded(true, "CONFIRMED")), TODAY))
                .isEqualTo(5);
    }

    private static Period excluded(boolean countInTotal, String state) {
        return new Period(
                LocalDate.of(2024, 3, 1),
                "MONTH",
                LocalDate.of(2024, 5, 1),
                "MONTH",
                false,
                null,
                null,
                countInTotal,
                state);
    }

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
