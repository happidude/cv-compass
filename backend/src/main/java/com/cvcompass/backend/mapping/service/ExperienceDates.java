package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PartialDate;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PeriodProposal;
import java.time.DateTimeException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finite grammar only, never operator-supplied regex. Invalid tokens retain raw evidence. */
final class ExperienceDates {
    private static final String MONTH =
            "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";
    private static final String DATE =
            "(?:[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}|[0-9]{4}-[0-9]{1,2}|[0-9]{1,2}[/.][0-9]{1,2}[/.][0-9]{4}|[0-9]{1,2}[/.][0-9]{4}|[0-9]{1,2}\\s+"
                    + MONTH
                    + "\\s+[0-9]{4}|"
                    + MONTH
                    + "\\s+[0-9]{4}|tháng\\s+[0-9]{1,2}/[0-9]{4}|[0-9]{4})";
    private static final Pattern RANGE =
            Pattern.compile(
                    "(?<![0-9])("
                            + DATE
                            + ")\\s*(?:[-–—]|to\\b|until\\b|đến\\b)\\s*("
                            + DATE
                            + "|now\\b|present\\b|current\\b|nay\\b|hiện tại\\b)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Map<String, Integer> MONTHS =
            Map.ofEntries(
                    Map.entry("jan", 1),
                    Map.entry("feb", 2),
                    Map.entry("mar", 3),
                    Map.entry("apr", 4),
                    Map.entry("may", 5),
                    Map.entry("jun", 6),
                    Map.entry("jul", 7),
                    Map.entry("aug", 8),
                    Map.entry("sep", 9),
                    Map.entry("oct", 10),
                    Map.entry("nov", 11),
                    Map.entry("dec", 12));

    static Matcher range(String text) {
        return RANGE.matcher(text);
    }

    static PeriodProposal parse(SourceLine line, int start, int end) {
        String raw = line.text.substring(start, end);
        Matcher match = range(raw.replaceFirst("[.]$", ""));
        if (!match.matches())
            return new PeriodProposal(
                    null,
                    null,
                    false,
                    line.span(start, end).text(),
                    "INVALID",
                    List.of(line.span(start, end)));
        boolean ongoing =
                List.of("now", "present", "current", "nay", "hiện tại")
                        .contains(match.group(2).toLowerCase(Locale.ROOT));
        PartialDate from = date(match.group(1)), to = ongoing ? null : date(match.group(2));
        boolean invalid =
                from == null
                        || !ongoing && to == null
                        || to != null && from != null && from.earliest().isAfter(to.latest());
        return new PeriodProposal(
                from,
                to,
                ongoing,
                line.span(start, end).text(),
                invalid ? "INVALID" : "PROPOSED",
                List.of(line.span(start, end)));
    }

    private static PartialDate date(String token) {
        try {
            String value = token.toLowerCase(Locale.ROOT).replaceFirst("^tháng\\s+", "");
            int year;
            Integer month = null, day = null;
            if (value.matches("[0-9]{4}")) year = Integer.parseInt(value);
            else if (value.matches("[0-9]{4}-[0-9]{1,2}(-[0-9]{1,2})?")) {
                String[] parts = value.split("-");
                year = Integer.parseInt(parts[0]);
                month = Integer.valueOf(parts[1]);
                if (parts.length == 3) day = Integer.valueOf(parts[2]);
            } else if (value.matches("[0-9]{1,2}[/.][0-9]{1,2}[/.][0-9]{4}")) {
                String[] parts = value.split("[/.]");
                day = Integer.valueOf(parts[0]);
                month = Integer.valueOf(parts[1]);
                year = Integer.parseInt(parts[2]);
            } else if (value.matches("[0-9]{1,2}[/.][0-9]{4}")) {
                String[] parts = value.split("[/.]");
                month = Integer.valueOf(parts[0]);
                year = Integer.parseInt(parts[1]);
            } else {
                String[] parts = value.split(" ");
                String name = parts[parts.length - 2];
                month = MONTHS.get(name.substring(0, 3));
                year = Integer.parseInt(parts[parts.length - 1]);
                if (parts.length == 3) day = Integer.valueOf(parts[0]);
            }
            if (year < 1 || year > 9999 || month != null && (month < 1 || month > 12)) return null;
            if (day != null) java.time.LocalDate.of(year, month, day);
            return new PartialDate(
                    year, month, day, day != null ? "DAY" : month != null ? "MONTH" : "YEAR");
        } catch (DateTimeException | NumberFormatException | IndexOutOfBoundsException invalid) {
            return null;
        }
    }
}
