package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PartialDate;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PeriodProposal;
import java.time.DateTimeException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Finite source date tokens; no clock, country inference, or inferred precision. */
final class ExperienceGrammarDates {
    private static final String MONTH =
            "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t|tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";
    private static final String DATE =
            "(?:[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}|[0-9]{4}-[0-9]{1,2}|[0-9]{1,2}[/.][0-9]{1,2}[/.][0-9]{4}|[0-9]{1,2}[/.][0-9]{4}|[0-9]{1,2}\\s+"
                    + MONTH
                    + "\\s+[0-9]{4}|"
                    + MONTH
                    + "[\\s-]+[0-9]{4}|tháng\\s+[0-9]{1,2}/[0-9]{4}|[0-9]{4})";
    private static final Pattern RANGE =
            Pattern.compile(
                    "(?iu)(?<![\\p{L}0-9])("
                            + DATE
                            + ")\\s*(?:[-–—]|to\\b|until\\b|đến\\b)\\s*("
                            + DATE
                            + "|now\\b|present\\b|current\\b|nay\\b|hiện tại\\b)(?![\\p{L}0-9])");
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

    record Capture(PeriodProposal proposal, int start, int end) {}

    static Capture find(SourceLine line) {
        var values = findAll(line);
        return values.isEmpty() ? null : values.getFirst();
    }

    static List<Capture> findAll(SourceLine line) {
        var values = new java.util.ArrayList<Capture>();
        var match = RANGE.matcher(line.text);
        while (match.find()) {
            boolean ongoing =
                    List.of("now", "present", "current", "nay", "hiện tại")
                            .contains(match.group(2).toLowerCase(Locale.ROOT));
            var start = date(match.group(1));
            var end = ongoing ? null : date(match.group(2));
            boolean invalid =
                    start == null
                            || !ongoing && end == null
                            || start != null
                                    && end != null
                                    && start.earliest().isAfter(end.latest());
            var evidence = line.span(match.start(), match.end());
            values.add(
                    new Capture(
                            new PeriodProposal(
                                    start,
                                    end,
                                    ongoing,
                                    evidence.text(),
                                    invalid ? "INVALID" : "PROPOSED",
                                    List.of(evidence)),
                            match.start(),
                            match.end()));
        }
        return List.copyOf(values);
    }

    static PeriodProposal labelled(SourceLine line, int start, int end) {
        var found = find(line);
        if (found != null && found.start() >= start && found.end() <= end) return found.proposal();
        while (start < end && Character.isWhitespace(line.text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(line.text.charAt(end - 1))) end--;
        var evidence = line.span(start, end);
        var value = date(line.text.substring(start, end));
        return new PeriodProposal(
                value,
                null,
                false,
                evidence.text(),
                value == null ? "INVALID" : "PROPOSED",
                List.of(evidence));
    }

    static boolean dateOnly(SourceLine line) {
        var capture = find(line);
        if (capture != null)
            return line.text.substring(0, capture.start()).matches("(?iu)(?:from|từ)?\\s*:?\\s*")
                    && line.text.substring(capture.end()).matches("[ .:：]*");
        return line.text.matches("[0-9]{4}");
    }

    private static PartialDate date(String token) {
        try {
            String value = token.strip().toLowerCase(Locale.ROOT).replaceFirst("^tháng\\s+", "");
            int year;
            Integer month = null, day = null;
            if (value.matches("[0-9]{4}")) year = Integer.parseInt(value);
            else if (value.matches("[0-9]{4}-[0-9]{1,2}(?:-[0-9]{1,2})?")) {
                var parts = value.split("-");
                year = Integer.parseInt(parts[0]);
                month = Integer.valueOf(parts[1]);
                if (parts.length == 3) day = Integer.valueOf(parts[2]);
            } else if (value.matches("[0-9]{1,2}[/.][0-9]{1,2}[/.][0-9]{4}")) {
                var parts = value.split("[/.]");
                year = Integer.parseInt(parts[2]);
                month = Integer.valueOf(parts[1]);
                day = Integer.valueOf(parts[0]);
            } else if (value.matches("[0-9]{1,2}[/.][0-9]{4}")) {
                var parts = value.split("[/.]");
                year = Integer.parseInt(parts[1]);
                month = Integer.valueOf(parts[0]);
            } else if (value.matches("(?iu)(?:[0-9]{1,2}\\s+)?" + MONTH + "[\\s-]+[0-9]{4}")) {
                var parts = value.replace('-', ' ').split("\\s+");
                String name = parts[parts.length - 2];
                month = MONTHS.get(name.substring(0, 3));
                year = Integer.parseInt(parts[parts.length - 1]);
                if (parts.length == 3) day = Integer.valueOf(parts[0]);
            } else return null;
            if (year < 1 || year > 9999 || month != null && (month < 1 || month > 12)) return null;
            if (day != null) java.time.LocalDate.of(year, month, day);
            return new PartialDate(
                    year, month, day, day != null ? "DAY" : month != null ? "MONTH" : "YEAR");
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
    }
}
