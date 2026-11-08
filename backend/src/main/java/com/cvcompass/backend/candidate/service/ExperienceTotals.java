package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.dto.ExperienceSummary;
import com.cvcompass.backend.candidate.dto.ExperienceSummary.SkipReason;
import com.cvcompass.backend.candidate.dto.ExperienceSummary.Skipped;
import com.cvcompass.backend.candidate.dto.ExperienceTotalsInput;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

final class ExperienceTotals {
    private static final class Interval {
        final LocalDate start;
        final LocalDate end;

        Interval(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }

        long months() {
            return Period.between(start, end.plusDays(1)).toTotalMonths();
        }
    }

    private ExperienceTotals() {}

    static ExperienceSummary calculate(
            List<ExperienceTotalsInput> entries, Map<Long, LocalDate> importedDates) {
        var newestDate =
                importedDates.values().stream().max(Comparator.naturalOrder()).orElse(null);
        var intervals = new ArrayList<Interval>();
        var counted = new ArrayList<UUID>();
        var skipped = new ArrayList<Skipped>();
        var ongoingDates = new TreeSet<LocalDate>();
        boolean hasYearOnlyPeriods = false;
        for (var entry : entries) {
            if (entry.deleted()) continue;
            var reference =
                    entry.documentId() == null ? newestDate : importedDates.get(entry.documentId());
            var start =
                    entry.startDate() == null
                            ? null
                            : earliest(entry.startDate(), entry.startPrecision());
            var reason = skipReason(entry, start, reference);
            if (reason != null) {
                skipped.add(new Skipped(entry.id(), reason));
                continue;
            }

            var end =
                    entry.ongoingAsStated()
                            ? reference
                            : latest(entry.endDate(), entry.endPrecision());
            if (reference != null && end.isAfter(reference)) end = reference;
            if (end.isBefore(start)) {
                skipped.add(new Skipped(entry.id(), SkipReason.PERIOD_UNRESOLVED));
                continue;
            }
            intervals.add(new Interval(start, end));
            counted.add(entry.id());
            if (entry.ongoingAsStated()) ongoingDates.add(reference);
            hasYearOnlyPeriods |=
                    "YEAR".equals(entry.startPrecision())
                            || !entry.ongoingAsStated() && "YEAR".equals(entry.endPrecision());
        }
        return new ExperienceSummary(
                unionMonths(intervals),
                counted,
                skipped,
                ongoingDates.stream().map(LocalDate::toString).toList(),
                hasYearOnlyPeriods);
    }

    private static SkipReason skipReason(
            ExperienceTotalsInput entry, LocalDate start, LocalDate reference) {
        if (!entry.countInTotal()) return SkipReason.EXCLUDED_BY_USER;
        if ("INVALID".equals(entry.periodState()) || "AMBIGUOUS".equals(entry.periodState()))
            return SkipReason.PERIOD_UNRESOLVED;
        if (start == null) return SkipReason.MISSING_START;
        if (!entry.ongoingAsStated() && entry.endDate() == null) return SkipReason.MISSING_END;
        if (reference == null && entry.ongoingAsStated()) return SkipReason.NO_REFERENCE_DATE;
        if (reference != null && start.isAfter(reference)) return SkipReason.START_AFTER_REFERENCE;
        return null;
    }

    private static LocalDate earliest(LocalDate date, String precision) {
        if ("YEAR".equals(precision)) return date.withDayOfYear(1);
        if ("MONTH".equals(precision)) return date.withDayOfMonth(1);
        return date;
    }

    private static LocalDate latest(LocalDate date, String precision) {
        if ("YEAR".equals(precision)) return date.withMonth(12).withDayOfMonth(31);
        if ("MONTH".equals(precision)) return YearMonth.from(date).atEndOfMonth();
        return date;
    }

    private static long unionMonths(List<Interval> intervals) {
        if (intervals.isEmpty()) return 0;
        intervals.sort(Comparator.comparing(interval -> interval.start));
        var merged = intervals.getFirst();
        long months = 0;
        for (int index = 1; index < intervals.size(); index++) {
            var next = intervals.get(index);
            if (next.start.isAfter(merged.end.plusDays(1))) {
                months += merged.months();
                merged = next;
            } else if (next.end.isAfter(merged.end)) {
                merged = new Interval(merged.start, next.end);
            }
        }
        return months + merged.months();
    }
}
