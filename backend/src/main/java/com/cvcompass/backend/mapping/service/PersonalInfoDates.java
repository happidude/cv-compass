package com.cvcompass.backend.mapping.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Date-of-birth grammar for 3.3. The normalized value keeps the stated precision (YYYY, YYYY-MM or
 * YYYY-MM-DD); a missing day or month is never filled in.
 */
final class PersonalInfoDates {
    record Parsed(String normalized, String issue) {}

    private static final List<String> MONTHS =
            List.of(
                    "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov",
                    "dec");
    private static final String MONTH =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
                    + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?";
    private static final String DAY = "(\\d{1,2})(?:st|nd|rd|th)?";
    private static final String YEAR = "((?:19|20)\\d{2})";
    private static final Pattern NUMERIC_DMY =
            Pattern.compile("(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})\\s*[/.-]\\s*" + YEAR);
    private static final Pattern NUMERIC_YMD =
            Pattern.compile(YEAR + "\\s*[/.-]\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})");
    private static final Pattern MONTH_YEAR = Pattern.compile("(\\d{1,2})\\s*[/.-]\\s*" + YEAR);
    private static final Pattern YEAR_ONLY = Pattern.compile(YEAR);
    private static final Pattern NAMED_MDY =
            Pattern.compile("(?iu)" + MONTH + "\\s*" + DAY + "\\s*,?\\s*" + YEAR);
    private static final Pattern NAMED_DMY =
            Pattern.compile("(?iu)" + DAY + "\\s*(?:of\\s+)?" + MONTH + "\\s*,?\\s*" + YEAR);
    private static final Pattern NAMED_MY = Pattern.compile("(?iu)" + MONTH + "\\s*,?\\s*" + YEAR);
    private static final Pattern VIETNAMESE =
            Pattern.compile(
                    "(?iu)(?:ngày\\s*)?(\\d{1,2})\\s*tháng\\s*(\\d{1,2})\\s*(?:năm|,)?\\s*" + YEAR);

    private PersonalInfoDates() {}

    static Parsed parse(String value) {
        String text = value.strip().replaceAll("[.,;]+$", "").strip();
        Matcher m;
        if ((m = NUMERIC_YMD.matcher(text)).matches())
            return day(num(m, 1), num(m, 2), num(m, 3), null);
        if ((m = NUMERIC_DMY.matcher(text)).matches()) {
            int first = num(m, 1), second = num(m, 2), year = num(m, 3);
            // Vietnamese CVs write day first; month-first is only accepted when day-first is
            // impossible, and both readings are flagged when they are both valid dates.
            if (second > 12 && first <= 12)
                return day(year, first, second, "DATE_OF_BIRTH_MONTH_FIRST");
            return day(
                    year,
                    second,
                    first,
                    first <= 12 && first != second ? "DATE_OF_BIRTH_DAY_FIRST_ASSUMED" : null);
        }
        if ((m = VIETNAMESE.matcher(text)).matches())
            return day(num(m, 3), num(m, 2), num(m, 1), null);
        if ((m = NAMED_MDY.matcher(text)).matches())
            return day(num(m, 3), month(m.group(1)), num(m, 2), null);
        if ((m = NAMED_DMY.matcher(text)).matches())
            return day(num(m, 3), month(m.group(2)), num(m, 1), null);
        if ((m = NAMED_MY.matcher(text)).matches()) return month(num(m, 2), month(m.group(1)));
        if ((m = MONTH_YEAR.matcher(text)).matches()) return month(num(m, 2), num(m, 1));
        if ((m = YEAR_ONLY.matcher(text)).matches()) return new Parsed(m.group(1), null);
        return new Parsed(null, "INVALID_DATE_OF_BIRTH");
    }

    /** A standalone full date with a plausible birth year, for the unlabeled fallback. */
    static boolean plausibleFullDate(String value) {
        var parsed = parse(value);
        if (parsed.normalized() == null || parsed.normalized().length() != 10) return false;
        int year = Integer.parseInt(parsed.normalized().substring(0, 4));
        return year >= 1940 && year <= 2015;
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }

    private static int month(String name) {
        return MONTHS.indexOf(name.toLowerCase(Locale.ROOT).substring(0, 3)) + 1;
    }

    private static Parsed day(int year, int month, int day, String issue) {
        try {
            return new Parsed(LocalDate.of(year, month, day).toString(), issue);
        } catch (DateTimeException e) {
            return new Parsed(null, "INVALID_DATE_OF_BIRTH");
        }
    }

    private static Parsed month(int year, int month) {
        if (month < 1 || month > 12) return new Parsed(null, "INVALID_DATE_OF_BIRTH");
        return new Parsed(String.format(Locale.ROOT, "%04d-%02d", year, month), null);
    }
}
