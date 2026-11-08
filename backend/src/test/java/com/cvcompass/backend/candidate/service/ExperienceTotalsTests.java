package com.cvcompass.backend.candidate.service;

import static com.cvcompass.backend.candidate.dto.ExperienceSummary.SkipReason.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.candidate.dto.ExperienceSummary.Skipped;
import com.cvcompass.backend.candidate.dto.ExperienceTotalsInput;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExperienceTotalsTests {
    private static final Map<Long, LocalDate> REFERENCES = Map.of(1L, date("2026-08-01"));

    @Test
    void unionsPartiallyOverlappingAndNestedIntervals() {
        var result =
                ExperienceTotals.calculate(
                        List.of(
                                closed(1, "2020-05-01", "2020-12-31"),
                                closed(2, "2020-03-01", "2020-05-31"),
                                closed(3, "2020-01-01", "2020-06-30")),
                        REFERENCES);
        assertThat(result.totalMonths()).isEqualTo(12);
        assertThat(result.countedIds()).containsExactly(id(1), id(2), id(3));
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void unionsAdjacentDaysBeforeDroppingPartialMonths() {
        var result =
                ExperienceTotals.calculate(
                        List.of(
                                closed(1, "2020-12-15", "2020-12-31"),
                                closed(2, "2021-01-01", "2021-01-14"),
                                closed(3, "2021-01-16", "2021-02-14")),
                        REFERENCES);
        assertThat(result.totalMonths()).isEqualTo(1);
    }

    @Test
    void usesFullCalendarBoundsForYearAndMonthPrecision() {
        var years =
                ExperienceTotals.calculate(
                        List.of(
                                entry(
                                        1,
                                        "2009-01-01",
                                        "YEAR",
                                        "2012-01-01",
                                        "YEAR",
                                        false,
                                        "PROPOSED",
                                        false,
                                        true,
                                        1L)),
                        REFERENCES);
        assertThat(years.totalMonths()).isEqualTo(48);
        assertThat(years.hasYearOnlyPeriods()).isTrue();

        var months =
                ExperienceTotals.calculate(
                        List.of(
                                entry(
                                        2,
                                        "2025-09-01",
                                        "MONTH",
                                        "2026-07-01",
                                        "MONTH",
                                        false,
                                        "PROPOSED",
                                        false,
                                        true,
                                        1L)),
                        REFERENCES);
        assertThat(months.totalMonths()).isEqualTo(11);
        assertThat(months.hasYearOnlyPeriods()).isFalse();
    }

    @Test
    void usesSourceDatesAndNewestLinkedDateForHrRowsAndCapsClosedIntervals() {
        var imports = Map.of(1L, date("2024-01-31"), 2L, date("2025-12-31"));
        var result =
                ExperienceTotals.calculate(
                        List.of(
                                entry(
                                        1,
                                        "2024-01-01",
                                        "MONTH",
                                        null,
                                        null,
                                        true,
                                        "PROPOSED",
                                        false,
                                        true,
                                        1L),
                                entry(
                                        2,
                                        "2025-12-01",
                                        "MONTH",
                                        null,
                                        null,
                                        true,
                                        "PROPOSED",
                                        false,
                                        true,
                                        null),
                                entry(
                                        3,
                                        "2023-12-01",
                                        "MONTH",
                                        "2024-03-01",
                                        "MONTH",
                                        false,
                                        "PROPOSED",
                                        false,
                                        true,
                                        1L)),
                        imports);
        assertThat(result.totalMonths()).isEqualTo(3);
        assertThat(result.ongoingReferenceDates()).containsExactly("2024-01-31", "2025-12-31");
    }

    @Test
    void reportsSkippedRowsAndOmitsDeletedRowsEntirely() {
        var result =
                ExperienceTotals.calculate(
                        List.of(
                                entry(
                                        1,
                                        null,
                                        null,
                                        "2023-12-31",
                                        "DAY",
                                        false,
                                        "PROPOSED",
                                        false,
                                        true,
                                        1L),
                                entry(
                                        2,
                                        "2023-01-01",
                                        "DAY",
                                        null,
                                        null,
                                        false,
                                        "PROPOSED",
                                        false,
                                        true,
                                        1L),
                                entry(
                                        3,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        "AMBIGUOUS",
                                        false,
                                        true,
                                        1L),
                                entry(4, null, null, null, null, false, "INVALID", false, true, 1L),
                                entry(
                                        5,
                                        "2023-01-01",
                                        "YEAR",
                                        null,
                                        null,
                                        true,
                                        "PROPOSED",
                                        false,
                                        false,
                                        1L),
                                entry(
                                        6,
                                        "2023-01-01",
                                        "YEAR",
                                        null,
                                        null,
                                        true,
                                        "PROPOSED",
                                        true,
                                        true,
                                        1L),
                                entry(
                                        7,
                                        "2026-09-01",
                                        "MONTH",
                                        null,
                                        null,
                                        true,
                                        "PROPOSED",
                                        false,
                                        true,
                                        1L),
                                entry(
                                        8,
                                        "2023-01-01",
                                        "DAY",
                                        null,
                                        null,
                                        true,
                                        "PROPOSED",
                                        false,
                                        true,
                                        9L)),
                        REFERENCES);
        assertThat(result.totalMonths()).isZero();
        assertThat(result.countedIds()).isEmpty();
        assertThat(result.ongoingReferenceDates()).isEmpty();
        assertThat(result.hasYearOnlyPeriods()).isFalse();
        assertThat(result.skipped())
                .containsExactly(
                        new Skipped(id(1), MISSING_START),
                        new Skipped(id(2), MISSING_END),
                        new Skipped(id(3), PERIOD_UNRESOLVED),
                        new Skipped(id(4), PERIOD_UNRESOLVED),
                        new Skipped(id(5), EXCLUDED_BY_USER),
                        new Skipped(id(7), START_AFTER_REFERENCE),
                        new Skipped(id(8), NO_REFERENCE_DATE));
    }

    @Test
    void countsClosedPeriodsWithoutAnImportButSkipsOngoingPeriods() {
        var result =
                ExperienceTotals.calculate(
                        List.of(
                                entry(
                                        1,
                                        "2024-01-01",
                                        "DAY",
                                        null,
                                        null,
                                        true,
                                        "PROPOSED",
                                        false,
                                        true,
                                        null),
                                entry(
                                        2,
                                        "2024-01-01",
                                        "DAY",
                                        "2024-01-31",
                                        "DAY",
                                        false,
                                        "PROPOSED",
                                        false,
                                        true,
                                        null),
                                closed(3, "2024-03-01", "2024-04-30")),
                        Map.of());
        assertThat(result.totalMonths()).isEqualTo(3);
        assertThat(result.countedIds()).containsExactly(id(2), id(3));
        assertThat(result.skipped()).containsExactly(new Skipped(id(1), NO_REFERENCE_DATE));
    }

    private static ExperienceTotalsInput closed(long id, String start, String end) {
        return entry(id, start, "DAY", end, "DAY", false, "PROPOSED", false, true, 1L);
    }

    private static ExperienceTotalsInput entry(
            long id,
            String start,
            String startPrecision,
            String end,
            String endPrecision,
            boolean ongoing,
            String state,
            boolean deleted,
            boolean countInTotal,
            Long documentId) {
        return new ExperienceTotalsInput(
                id(id),
                date(start),
                startPrecision,
                date(end),
                endPrecision,
                ongoing,
                state,
                deleted,
                countInTotal,
                documentId);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
