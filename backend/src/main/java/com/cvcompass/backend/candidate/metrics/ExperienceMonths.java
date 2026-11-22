package com.cvcompass.backend.candidate.metrics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Month-level metrics for the search snapshot, independent of document import dates. */
public final class ExperienceMonths {
    private ExperienceMonths() {}

    public record Period(
            LocalDate start,
            String startPrecision,
            LocalDate end,
            String endPrecision,
            boolean ongoing,
            Long companyId,
            Long titleId,
            boolean countInTotal,
            String periodState) {
        public Period(
                LocalDate start,
                String startPrecision,
                LocalDate end,
                String endPrecision,
                boolean ongoing,
                Long companyId,
                Long titleId) {
            this(start, startPrecision, end, endPrecision, ongoing, companyId, titleId, true, null);
        }
    }

    private record Range(YearMonth start, YearMonth end) {}

    public static int total(List<Period> periods, LocalDate today) {
        var ranges = new ArrayList<Range>();
        for (var period : periods) {
            // Same exclusions as the HR-visible total (ExperienceTotals).
            if (!period.countInTotal()
                    || "INVALID".equals(period.periodState())
                    || "AMBIGUOUS".equals(period.periodState())) continue;
            if (period.start() == null || !period.ongoing() && period.end() == null) continue;
            boolean sameYearOnly =
                    !period.ongoing()
                            && "YEAR".equals(period.startPrecision())
                            && "YEAR".equals(period.endPrecision())
                            && period.start().getYear() == period.end().getYear();
            if (!period.ongoing() && !sameYearOnly && period.end().isBefore(period.start()))
                continue;
            var start = start(period);
            var end = period.ongoing() ? YearMonth.from(today) : end(period);
            // Two unknown months in the same year contribute one conservative month.
            if (sameYearOnly) end = start;
            if (!end.isBefore(start)) ranges.add(new Range(start, end));
        }
        ranges.sort(Comparator.comparing(Range::start));
        Range merged = null;
        long months = 0;
        for (var range : ranges) {
            if (merged == null) merged = range;
            else if (ChronoUnit.MONTHS.between(merged.end(), range.start()) > 1) {
                months += ChronoUnit.MONTHS.between(merged.start(), merged.end()) + 1;
                merged = range;
            } else if (range.end().isAfter(merged.end()))
                merged = new Range(merged.start(), range.end());
        }
        if (merged != null) months += ChronoUnit.MONTHS.between(merged.start(), merged.end()) + 1;
        return Math.toIntExact(months);
    }

    public static Optional<Period> current(List<Period> periods) {
        var byStart =
                Comparator.comparing(
                        ExperienceMonths::currentStart,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
        var ongoing = periods.stream().filter(Period::ongoing).max(byStart);
        if (ongoing.isPresent()) return ongoing;
        return periods.stream()
                .filter(p -> p.end() != null)
                .max(Comparator.comparing(ExperienceMonths::currentEnd).thenComparing(byStart));
    }

    private static LocalDate currentStart(Period period) {
        if (period.start() == null) return null;
        return "YEAR".equals(period.startPrecision()) ? start(period).atDay(1) : period.start();
    }

    private static LocalDate currentEnd(Period period) {
        return "YEAR".equals(period.endPrecision()) ? end(period).atDay(1) : period.end();
    }

    private static YearMonth start(Period period) {
        var month = YearMonth.from(period.start());
        return "YEAR".equals(period.startPrecision()) ? month.withMonth(12) : month;
    }

    private static YearMonth end(Period period) {
        var month = YearMonth.from(period.end());
        return "YEAR".equals(period.endPrecision()) ? month.withMonth(1) : month;
    }
}
