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
    private static final List<String> MONTH_NAMES =
            List.of(
                    "january",
                    "february",
                    "march",
                    "april",
                    "may",
                    "june",
                    "july",
                    "august",
                    "september",
                    "october",
                    "november",
                    "december");
    private static final String NONSTANDARD =
            String.join(
                    "|",
                    MONTH_NAMES.stream()
                            .flatMap(
                                    name ->
                                            java.util.stream.IntStream.range(3, name.length())
                                                    .mapToObj(length -> name.substring(0, length)))
                            .filter(prefix -> !prefix.matches("(?i)" + MONTH))
                            .toList());
    private static final String MONTH_V34 = "(?:" + MONTH + "|" + NONSTANDARD + ")";
    private static final Pattern NONSTANDARD_MONTH =
            Pattern.compile("(?i)\\b(?:" + NONSTANDARD + ")\\b");
    private static final String DATE_V34 =
            "(?:[0-9]{4}\\s+"
                    + MONTH_V34
                    + "\\b|(?:[0-9]{1,2}\\s+)?"
                    + MONTH_V34
                    + "(?:[’']?\\s*[-–—]\\s*|\\s+)[0-9]{4}"
                    + "|tháng\\s+[0-9]{1,2}/\\s*[0-9]{4}|"
                    + DATE
                    + ")";
    private static final Pattern RANGE = rangePattern(DATE);
    private static final Pattern RANGE_V34 = rangePattern(DATE_V34);

    private static Pattern rangePattern(String dateToken) {
        return Pattern.compile(
                "(?iu)(?<![\\p{L}0-9])("
                        + dateToken
                        + ")\\s*(?:[-–—]|to\\b|until\\b|đến\\b)\\s*("
                        + dateToken
                        + "|now\\b|present\\b|current\\b|nay\\b|hiện tại\\b)(?![\\p{L}0-9])");
    }

    private final boolean v34;
    private final Pattern range;

    ExperienceGrammarDates(boolean v34) {
        this.v34 = v34;
        this.range = v34 ? RANGE_V34 : RANGE;
    }

    int withoutLeadingRangeConnector(SourceLine line, Capture capture) {
        return v34
                ? line.text
                        .substring(0, capture.start())
                        .replaceFirst("(?iu)(?:^|\\s|(?<=\\())(?:from|từ)\\s+$", "")
                        .length()
                : capture.start();
    }

    boolean hasNonstandardMonth(String raw) {
        return v34 && NONSTANDARD_MONTH.matcher(raw).find();
    }

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

    private static final Pattern MONTH_SLOT_BEFORE_YEAR =
            Pattern.compile(
                    "(?i)(?:^|[|:]\\s*|\\b(?:ltd\\.?|limited|inc\\.?|corp\\.?|corporation|llc|jsc|co\\.)\\s+)([a-z]{3})\\s+$");

    record Capture(PeriodProposal proposal, int start, int end) {}

    private static boolean transposedMonthInDateSlot(String prefix) {
        var match = MONTH_SLOT_BEFORE_YEAR.matcher(prefix);
        if (!match.find()) return false;
        String token = match.group(1).toLowerCase(Locale.ROOT);
        if (MONTHS.containsKey(token)) return false;
        return MONTHS.containsKey("" + token.charAt(1) + token.charAt(0) + token.charAt(2))
                || MONTHS.containsKey("" + token.charAt(0) + token.charAt(2) + token.charAt(1));
    }

    boolean startsWithRange(String text) {
        return range.matcher(text).lookingAt();
    }

    boolean hasRange(String text) {
        return range.matcher(text).find();
    }

    Capture find(SourceLine line) {
        var values = findAll(line);
        return values.isEmpty() ? null : values.getFirst();
    }

    List<Capture> findAll(SourceLine line) {
        var values = new java.util.ArrayList<Capture>();
        var match = range.matcher(line.text);
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
                                    && start.earliest().isAfter(end.latest())
                            || v34
                                    && start != null
                                    && "YEAR".equals(start.precision())
                                    && end != null
                                    && "MONTH".equals(end.precision())
                                    && transposedMonthInDateSlot(
                                            line.text.substring(0, match.start()));
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

    PeriodProposal labelled(SourceLine line, int start, int end) {
        var found = find(line);
        if (found != null
                && found.start() >= start
                && found.end() <= end
                && (!v34
                        || line.text
                                        .substring(start, found.start())
                                        .matches("(?iu)\\s*\\(?\\s*(?:from|từ)?\\s*")
                                && line.text.substring(found.end(), end).matches("[\\s().:：|/]*")))
            return found.proposal();
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

    boolean dateOnly(SourceLine line) {
        var capture = find(line);
        if (capture != null)
            return line.text.substring(0, capture.start()).matches("(?iu)(?:from|từ)?\\s*:?\\s*")
                    && line.text.substring(capture.end()).matches("[ .:：]*");
        return line.text.matches("[0-9]{4}");
    }

    private PartialDate date(String token) {
        try {
            String value = token.strip().toLowerCase(Locale.ROOT).replaceFirst("^tháng\\s+", "");
            if (v34) {
                value =
                        value.replaceFirst("(?i)^([0-9]{4})\\s+(" + MONTH_V34 + ")$", "$2 $1")
                                .replaceFirst(
                                        "(?i)^(" + MONTH_V34 + ")[’']?\\s*[-–—]\\s*([0-9]{4})$",
                                        "$1 $2")
                                .replaceFirst("^([0-9]{1,2}/)\\s+([0-9]{4})$", "$1$2");
            }
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
            } else if (value.matches(
                    "(?iu)(?:[0-9]{1,2}\\s+)?" + (v34 ? MONTH_V34 : MONTH) + "[\\s-]+[0-9]{4}")) {
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
