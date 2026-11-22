package com.cvcompass.backend.candidate.metrics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Current-position selection for the search snapshot. */
public final class ExperienceMonths {
    private ExperienceMonths() {}

    public record Period(
            LocalDate start,
            String startPrecision,
            LocalDate end,
            String endPrecision,
            boolean ongoing,
            Long companyId,
            Long titleId) {}

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
