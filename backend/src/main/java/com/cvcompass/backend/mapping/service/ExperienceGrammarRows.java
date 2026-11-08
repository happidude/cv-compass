package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PeriodProposal;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Typed finite row anchors establish relationships; whitespace columns do not. */
final class ExperienceGrammarRows {
    private static final Pattern COMPANY =
            Pattern.compile(
                    "(?iu).+\\b(?:ltd\\.?|limited|inc\\.?|corporation|corp\\.?|company|group|llc|jsc|co\\.?)(?:\\s*\\([^)]{1,80}\\))?$");
    private static final Pattern ROLE =
            Pattern.compile(
                    "(?iu)\\b(?:engineer|manager|director|developer|analyst|officer|accountant|auditor|supervisor|consultant|specialist|president|CEO|CFO|COO|founder|co-founder|kỹ sư|giám đốc|trưởng phòng|chuyên viên)\\b");
    private static final Pattern ROLE_V34 =
            Pattern.compile(
                    "(?iu)\\b(?:coordinator|executive|technician|assistant|intern|trainee|associate|representative|advisor|buyer|merchandiser|planner|receptionist|purchaser|AGM|GM|head|nhân viên|trưởng nhóm|giám sát|quản lý|kế toán|thực tập sinh)\\b");
    private static final Pattern GERUND_PROSE =
            Pattern.compile(
                    "(?iu)^(?!(?:(?:engineering|marketing|accounting|purchasing|planning|training|operating|manufacturing|recruiting|staffing|shipping|billing|consulting)\\s+(?:manager|director|officer|supervisor|assistant|coordinator|specialist|executive|engineer|analyst|associate)|managing director)\\b)\\p{L}+ing\\b.*");
    private static final Pattern CONTEXT =
            Pattern.compile(
                    "(?iu)^(?:report(?:ing|s)? to|client|supplier|customer|platform|project|brand|group|training|course|certification|study abroad)(?:\\s*[:：]|\\s+)\\s*.*");
    private static final Pattern PROSE =
            Pattern.compile(
                    "(?iu)^(?:worked|working|managed|manage|led|built|responsible|supported|cooperated|collaborated|studied|reported|reporting|developed)\\b.*");
    private static final Pattern STATEMENT =
            Pattern.compile(
                    "(?iu)^(?:worked at|working at|have been working at|làm việc tại)\\s+(.+?)\\s+(?:from|từ)\\s+.+$");
    private static final Pattern APPLICATION =
            Pattern.compile(
                    "(?iu)^(?:application for|application to|applying for|ứng tuyển|position applied for)\\b.*");
    private static final Pattern MODIFIER =
            Pattern.compile(
                    "(?iu)(?:senior|junior|deputy|assistant|acting|regional|global|head of|chief|lead)");

    private static final Pattern COMPANY_PREFIX =
            Pattern.compile("(?iu)^(?:công ty|cty|ctcp|tnhh|tập đoàn)\\s+.+");
    private static final String HEADING_SEPARATOR = "\\||\\s+[/–—-]\\s+";
    private static final Pattern HEADING_PART =
            Pattern.compile("\\([^()]*\\)|" + HEADING_SEPARATOR);
    private static final Pattern TITLE_SEPARATOR = Pattern.compile(HEADING_SEPARATOR);
    private static final Pattern BULLET = Pattern.compile("^(?:[-–—*•●▪]|\\d+[.)])\\s+.*|^[•●▪].*");
    private static final Set<String> REGIONS =
            Set.of(
                    "vietnam",
                    "việt nam",
                    "singapore",
                    "thailand",
                    "malaysia",
                    "indonesia",
                    "philippines",
                    "sea",
                    "apac");

    record Atom(String raw, String text, List<Evidence> evidence) {
        Atom {
            evidence = List.copyOf(evidence);
        }
    }

    record Row(
            SourceLine source,
            Atom company,
            List<Atom> titles,
            List<PeriodProposal> periods,
            int consumedThrough,
            boolean continued,
            boolean ambiguous,
            boolean contextOnly) {}

    private record Label(String field, Pattern pattern, String key) {}

    private record Match(String field, int start, int valueStart) {}

    private final Extraction input;
    private final ExperienceDefinition config;
    private final List<SourceLine> lines;
    private final List<Label> labels;

    ExperienceGrammarRows(Extraction input, ExperienceDefinition config, List<SourceLine> lines) {
        this.input = input;
        this.config = config;
        this.lines = lines;
        var result = new ArrayList<Label>();
        addLabels(result, "company", config.rules().employerLabels());
        addLabels(result, "title", config.rules().titleLabels());
        addLabels(result, "period", config.rules().periodLabels());
        result.sort(Comparator.comparingInt((Label label) -> label.key().length()).reversed());
        labels = List.copyOf(result);
    }

    private static void addLabels(List<Label> result, String field, List<String> values) {
        for (String value : values)
            result.add(
                    new Label(
                            field,
                            Pattern.compile(
                                    "(?iu)(?<![\\p{L}0-9])"
                                            + Pattern.quote(ExperienceDefinition.key(value))
                                            + "\\s*[:：]"),
                            ExperienceDefinition.key(value)));
    }

    Row parse(int index, boolean anchored) {
        var line = lines.get(index);
        if (CONTEXT.matcher(line.text).matches() || APPLICATION.matcher(line.text).matches())
            return new Row(line, null, List.of(), List.of(), index, false, false, true);
        var statement = STATEMENT.matcher(line.text);
        var statementPeriod = ExperienceGrammarDates.find(line);
        if (statement.matches() && statementPeriod != null)
            return new Row(
                    line,
                    atom(line, statement.start(1), statement.end(1)),
                    List.of(),
                    List.of(statementPeriod.proposal()),
                    index,
                    false,
                    false,
                    false);
        var companies = new ArrayList<Atom>();
        var titles = new ArrayList<Atom>();
        var periods = new ArrayList<PeriodProposal>();
        boolean continued = false, ambiguous = false;
        int consumed = index;
        var matches = matches(line.text, anchored);
        if (!matches.isEmpty()) {
            for (int n = 0; n < matches.size(); n++) {
                var label = matches.get(n);
                int end = n + 1 < matches.size() ? matches.get(n + 1).start() : line.text.length();
                Atom value = atom(line, label.valueStart(), end);
                SourceLine valueLine = line;
                if (value == null && index + 1 < lines.size()) {
                    var next = lines.get(index + 1);
                    if (next.page == line.page
                            && matches(next.text, anchored).isEmpty()
                            && !heading(next.text)) {
                        value = atom(next, 0, next.text.length());
                        valueLine = next;
                        consumed = index + 1;
                    }
                }
                if (value == null) {
                    ambiguous = true;
                    continue;
                }
                if (label.field().equals("period")) {
                    int valueStart = valueLine == line ? label.valueStart() : 0;
                    int valueEnd = valueLine == line ? end : valueLine.text.length();
                    var captures =
                            ExperienceGrammarDates.findAll(valueLine).stream()
                                    .filter(
                                            capture ->
                                                    capture.start() >= valueStart
                                                            && capture.end() <= valueEnd)
                                    .toList();
                    if (captures.isEmpty())
                        periods.add(
                                ExperienceGrammarDates.labelled(valueLine, valueStart, valueEnd));
                    else captures.forEach(capture -> periods.add(capture.proposal()));
                } else {
                    if (CONTEXT.matcher(value.text()).matches()) continue;
                    var captures = ExperienceGrammarDates.findAll(valueLine);
                    int start = valueLine == line ? label.valueStart() : 0;
                    int valueEnd = valueLine == line ? end : valueLine.text.length();
                    var embedded =
                            captures.stream()
                                    .filter(
                                            capture ->
                                                    capture.start() >= start
                                                            && capture.end() <= valueEnd)
                                    .toList();
                    if (embedded.size() > 1) {
                        ambiguous = true;
                        continue;
                    }
                    if (!embedded.isEmpty()) {
                        var capture = embedded.getFirst();
                        periods.add(capture.proposal());
                        int prefixEnd = capture.start();
                        if (prefixEnd > start && valueLine.text.charAt(prefixEnd - 1) == '(')
                            prefixEnd--;
                        int suffixStart = capture.end();
                        if (suffixStart < valueEnd && valueLine.text.charAt(suffixStart) == ')')
                            suffixStart++;
                        var before = atom(valueLine, start, prefixEnd);
                        var after = atom(valueLine, suffixStart, valueEnd);
                        if (after != null && after.text().matches("[().;:：]*")) after = null;
                        if ((before == null) == (after == null)) {
                            ambiguous = true;
                            continue;
                        }
                        value = before == null ? after : before;
                    }
                    if (label.field().equals("company")) {
                        var cleaned = stripContinuation(valueLine, value);
                        continued |= cleaned != value;
                        value = cleaned;
                        if (consumed + 1 < lines.size() && !company(value.text(), anchored)) {
                            var next = lines.get(consumed + 1);
                            if (next.page == line.page
                                    && company(value.text() + " " + next.text, anchored)
                                    && matches(next.text, anchored).isEmpty()) {
                                var spans = new ArrayList<>(value.evidence());
                                spans.add(next.whole());
                                value = atom(spans);
                                consumed++;
                            }
                        }
                        companies.add(value);
                    } else if (!CONTEXT.matcher(value.text()).matches()) {
                        if (MODIFIER.matcher(value.text()).matches()
                                && consumed + 1 < lines.size()) {
                            var next = lines.get(consumed + 1);
                            if (next.page == line.page
                                    && title(next.text, anchored)
                                    && matches(next.text, anchored).isEmpty()) {
                                var spans = new ArrayList<>(value.evidence());
                                spans.add(next.whole());
                                value = atom(spans);
                                consumed++;
                            }
                        }
                        titles.add(value);
                    }
                }
            }
        } else {
            if (anchored) {
                var headingRow = anchoredRow(index);
                if (headingRow != null) return headingRow;
            }
            var capture = ExperienceGrammarDates.find(line);
            if (line.raw.matches("(?s).*\\S(?:\\t| {3,})\\S.*")
                    && !line.text.contains("|")
                    && capture != null
                    && !ExperienceGrammarDates.dateOnly(line))
                return new Row(line, null, List.of(), List.of(), index, false, true, false);
            if (line.text.contains("|") || line.text.contains(" / ")) {
                var parts =
                        Pattern.compile(line.text.contains("|") ? "[^|]+" : "[^/]+")
                                .matcher(line.text);
                while (parts.find()) {
                    var value = atom(line, parts.start(), parts.end());
                    if (value == null) continue;
                    if (capture != null
                            && capture.start() >= parts.start()
                            && capture.end() <= parts.end()) periods.add(capture.proposal());
                    else if (value.text().matches("[0-9]{4}"))
                        periods.add(
                                ExperienceGrammarDates.labelled(line, parts.start(), parts.end()));
                    else if (company(value.text(), anchored)) companies.add(value);
                    else if (title(value.text(), anchored)) titles.add(value);
                    else ambiguous = true;
                }
            } else if (capture != null) {
                periods.add(capture.proposal());
                if (!ExperienceGrammarDates.dateOnly(line)) {
                    int beforeEnd = capture.start();
                    if (beforeEnd > 0 && line.text.charAt(beforeEnd - 1) == '(') beforeEnd--;
                    int afterStart = capture.end();
                    if (afterStart < line.text.length() && line.text.charAt(afterStart) == ')')
                        afterStart++;
                    var before = atom(line, 0, beforeEnd);
                    var after = atom(line, afterStart, line.text.length());
                    for (var value :
                            List.of(
                                    before == null ? empty() : before,
                                    after == null ? empty() : after)) {
                        if (value.raw().isEmpty() || value.text().matches("[().;:：]*")) continue;
                        if (company(value.text(), anchored)) companies.add(value);
                        else if (title(value.text(), anchored)) titles.add(value);
                        else ambiguous = true;
                    }
                }
            } else if (line.text.matches("[0-9]{4}"))
                periods.add(ExperienceGrammarDates.labelled(line, 0, line.text.length()));
            else {
                if (line.text.codePointCount(0, line.text.length()) > 4096)
                    return new Row(line, null, List.of(), List.of(), index, false, false, true);
                var value = atom(line, 0, line.text.length());
                if (value == null)
                    return new Row(line, null, List.of(), List.of(), index, false, false, true);
                var cleaned = stripContinuation(line, value);
                continued = value != cleaned;
                if (company(cleaned.text(), anchored)) companies.add(cleaned);
                else if (title(value.text(), anchored)) titles.add(value);
                else if (MODIFIER.matcher(value.text()).matches()
                        && index + 1 < lines.size()
                        && lines.get(index + 1).page == line.page
                        && title(lines.get(index + 1).text, anchored)) {
                    titles.add(
                            atom(
                                    List.of(
                                            value.evidence().getFirst(),
                                            lines.get(index + 1).whole())));
                    consumed++;
                }
            }
        }
        for (int i = 0; i < companies.size(); i++) {
            var original = companies.get(i);
            var cleaned = stripContinuation(line, original);
            continued |= cleaned != original;
            companies.set(i, cleaned);
        }
        return new Row(
                line,
                companies.size() == 1 ? companies.getFirst() : null,
                List.copyOf(titles),
                List.copyOf(periods),
                consumed,
                continued,
                ambiguous || companies.size() > 1,
                false);
    }

    Row contextRow(int index) {
        var row = anchoredRow(index);
        return row != null && row.company() != null && !row.periods().isEmpty() && !row.ambiguous()
                ? row
                : null;
    }

    boolean adjacentTitle(int index) {
        var line = nearbyHeadingText(index, index);
        return line != null && title(line.text, true);
    }

    private Row anchoredRow(int index) {
        var line = lines.get(index);
        var captures = ExperienceGrammarDates.findAll(line);
        if (captures.isEmpty()) {
            if (index + 1 >= lines.size()) return null;
            var next = lines.get(index + 1);
            if (next.page != line.page || !wrappedPeriod(next)) return null;
            var company = followingCompany(index, index);
            return company == null
                    ? null
                    : new Row(line, company, List.of(), List.of(), index, false, false, false);
        }
        var capture = captures.getFirst();
        if (captures.size() != 1)
            return new Row(line, null, List.of(), List.of(), index, false, true, false);

        int start = capture.start();
        int end = capture.end();
        // Strip only parentheses enclosing the period, including inner whitespace.
        int left = line.text.substring(0, start).stripTrailing().length();
        int right = line.text.length() - line.text.substring(end).stripLeading().length();
        if (left > 0
                && right < line.text.length()
                && line.text.charAt(left - 1) == '('
                && line.text.charAt(right) == ')') {
            start = left - 1;
            end = right + 1;
        }
        if (ExperienceGrammarDates.dateOnly(line)
                || start == 0 && line.text.substring(end).isBlank()) {
            var next = nearbyHeadingText(index, index + 1);
            if (next != null && title(next.text, true) && !wrappedPeriod(line)) {
                var company = followingCompany(index, index + 2);
                if (company != null)
                    return new Row(
                            line,
                            company,
                            List.of(atom(next, 0, next.text.length())),
                            List.of(capture.proposal()),
                            index + 2,
                            false,
                            false,
                            false);
            }
            return new Row(
                    line, null, List.of(), List.of(capture.proposal()), index, false, false, false);
        }

        var before = atom(line, 0, start);
        var after = atom(line, end, line.text.length());
        String remainder =
                ((before == null ? "" : before.text()) + " " + (after == null ? "" : after.text()))
                        .strip();
        if (!shortHeading(remainder) || BULLET.matcher(line.text).matches())
            return new Row(line, null, List.of(), List.of(), index, false, false, true);

        var parts = new ArrayList<Atom>();
        headingParts(line, 0, start, parts);
        headingParts(line, end, line.text.length(), parts);
        var companies = new ArrayList<Atom>();
        var titles = new ArrayList<Atom>();
        for (var part : parts) {
            if (location(part.text())) continue;
            if (title(part.text(), true)) titles.add(part);
            else companies.add(part);
        }
        var company = companies.size() == 1 ? companies.getFirst() : null;
        int consumed = index;
        if (companies.isEmpty() && !titles.isEmpty()) {
            company = followingCompany(index, index + 1);
            if (company != null) consumed++;
        }
        boolean continued = false;
        if (company != null && consumed == index) {
            var cleaned = stripContinuation(line, company);
            continued = cleaned != company;
            company = cleaned;
        }
        return new Row(
                line,
                company,
                titles,
                List.of(capture.proposal()),
                consumed,
                continued,
                companies.size() > 1 || companies.isEmpty() && titles.isEmpty(),
                false);
    }

    private static boolean wrappedPeriod(SourceLine line) {
        var capture = ExperienceGrammarDates.find(line);
        return capture != null
                && line.text.substring(0, capture.start()).strip().equals("(")
                && line.text.substring(capture.end()).strip().equals(")");
    }

    private Atom followingCompany(int anchor, int index) {
        var next = nearbyHeadingText(anchor, index);
        if (next == null) return null;
        var parts = new ArrayList<Atom>();
        headingParts(next, 0, next.text.length(), parts);
        var values = parts.stream().filter(part -> !location(part.text())).toList();
        return values.size() == 1 && !title(values.getFirst().text(), true)
                ? values.getFirst()
                : null;
    }

    private SourceLine nearbyHeadingText(int anchor, int index) {
        if (index >= lines.size()) return null;
        var next = lines.get(index);
        return next.page == lines.get(anchor).page
                        && shortHeading(next.text)
                        && !heading(next.text)
                        && !contextHeading(
                                ExperienceDefinition.key(next.text.replaceFirst("[:：]$", "")), true)
                        && matches(next.text, true).isEmpty()
                        && !next.text.matches("[0-9]{4}")
                        && ExperienceGrammarDates.find(next) == null
                ? next
                : null;
    }

    private void headingParts(SourceLine line, int start, int end, List<Atom> parts) {
        var separators = HEADING_PART.matcher(line.text).region(start, end);
        while (separators.find()) {
            if (separators.group().startsWith("(")) continue;
            var part = atom(line, start, separators.start());
            if (part != null) parts.add(part);
            start = separators.end();
        }
        var last = atom(line, start, end);
        if (last != null) parts.add(last);
    }

    private static boolean shortHeading(String text) {
        return text.length() <= 160
                && text.split(" ").length <= 14
                && !text.endsWith(".")
                && !PROSE.matcher(text).matches()
                && !GERUND_PROSE.matcher(text).matches()
                && !BULLET.matcher(text).matches()
                && !CONTEXT.matcher(text).matches()
                && !APPLICATION.matcher(text).matches();
    }

    private static boolean location(String text) {
        if (REGIONS.contains(ExperienceDefinition.key(text))) return true;
        var province = PersonalInfoProvinces.find(text);
        return province != null && province.start() == 0 && province.end() == text.length();
    }

    boolean explicitEmployment(SourceLine line) {
        return matches(line.text, false).stream().anyMatch(label -> label.field().equals("company"))
                || STATEMENT.matcher(line.text).matches();
    }

    private static Atom empty() {
        return new Atom("", "", List.of());
    }

    private boolean company(String text, boolean v34) {
        boolean configured =
                config.companies().stream()
                        .anyMatch(
                                rule ->
                                        rule.aliases().stream()
                                                .anyMatch(
                                                        alias ->
                                                                ExperienceDefinition.key(alias)
                                                                        .equals(
                                                                                ExperienceDefinition
                                                                                        .key(
                                                                                                text))));
        String companyText = v34 ? text.replaceAll("\\([^()]*\\)", "") : text;
        return configured
                || !PROSE.matcher(text).matches()
                        && !CONTEXT.matcher(text).matches()
                        && !companyText.contains(":")
                        && (COMPANY.matcher(text).matches()
                                || v34 && COMPANY_PREFIX.matcher(text).matches()
                                || config.companies().stream()
                                        .anyMatch(
                                                rule ->
                                                        rule.aliases().stream()
                                                                .anyMatch(
                                                                        alias ->
                                                                                ExperienceDefinition
                                                                                        .key(alias)
                                                                                        .equals(
                                                                                                ExperienceDefinition
                                                                                                        .key(
                                                                                                                text)))));
    }

    private boolean title(String text, boolean v34) {
        if (company(text, v34)
                || PROSE.matcher(text).matches()
                || CONTEXT.matcher(text).matches()
                || text.endsWith(".")
                || text.length() > 160
                || text.split(" ").length > 14
                || text.contains(":")
                || text.matches("(?iu).*(?:CEO|CFO|COO).*(?:course|training|program).*"))
            return false;
        if (v34
                && (BULLET.matcher(text).matches()
                        || GERUND_PROSE.matcher(text).matches()
                        || text.endsWith(",")
                        || text.indexOf(',') != text.lastIndexOf(',')
                        || text.split(" ").length > 8 && !TITLE_SEPARATOR.matcher(text).find()))
            return false;
        return ROLE.matcher(text).find()
                || v34 && ROLE_V34.matcher(text).find()
                || config.titles().stream()
                        .anyMatch(
                                rule ->
                                        rule.aliases().stream()
                                                .anyMatch(
                                                        alias ->
                                                                ExperienceDefinition.key(alias)
                                                                        .equals(
                                                                                ExperienceDefinition
                                                                                        .key(
                                                                                                text))));
    }

    private boolean heading(String text) {
        String key = ExperienceDefinition.key(text.replaceFirst("[:：]$", ""));
        return java.util.stream.Stream.of(
                        config.rules().experienceHeadings(),
                        config.rules().summaryHeadings(),
                        config.rules().stopHeadings())
                .flatMap(List::stream)
                .anyMatch(value -> ExperienceDefinition.key(value).equals(key));
    }

    static boolean contextHeading(String key, boolean v34) {
        return key.matches("(?iu)(?:advisory|consulting|shareholder|co-founder|projects|dự án)")
                || v34
                        && key.matches(
                                "(?iu)(?:advisory board(?:\\s*/\\s*share\\s*holder)?|share holder|other projects)");
    }

    private List<Match> matches(String text, boolean v34) {
        var result = new ArrayList<Match>();
        for (var label : labels) {
            var matcher = label.pattern().matcher(text);
            while (matcher.find()) {
                if (v34 && insideParentheses(text, matcher.start())) continue;
                if (result.stream()
                        .noneMatch(
                                old ->
                                        matcher.start() < old.valueStart()
                                                && matcher.end() > old.start()))
                    result.add(new Match(label.field(), matcher.start(), matcher.end()));
            }
            if (result.isEmpty()
                    && ExperienceDefinition.key(text.replaceFirst("[:：]$", "")).equals(label.key()))
                result.add(new Match(label.field(), 0, text.length()));
        }
        result.sort(Comparator.comparingInt(Match::start));
        return result;
    }

    private static boolean insideParentheses(String text, int end) {
        int depth = 0;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '(') depth++;
            else if (text.charAt(i) == ')' && depth > 0) depth--;
        }
        return depth > 0;
    }

    private Atom stripContinuation(SourceLine line, Atom value) {
        for (String marker : config.rules().continuationMarkers()) {
            var matcher =
                    Pattern.compile("(?iu)\\s*\\(" + Pattern.quote(marker) + "\\)$")
                            .matcher(value.text());
            if (matcher.find()) {
                // Captures are on the same normalized source line, before the trailing marker.
                int start = line.text.indexOf(value.text());
                if (start >= 0) return atom(line, start, start + matcher.start());
            }
        }
        return value;
    }

    private Atom atom(SourceLine line, int start, int end) {
        while (start < end
                && (Character.isWhitespace(line.text.charAt(start))
                        || "|/:：".indexOf(line.text.charAt(start)) >= 0)) start++;
        while (end > start
                && (Character.isWhitespace(line.text.charAt(end - 1))
                        || "|/：:".indexOf(line.text.charAt(end - 1)) >= 0)) end--;
        return start == end ? null : atom(List.of(line.span(start, end)));
    }

    private Atom atom(List<Evidence> evidence) {
        String raw = PersonalInfoGrammarLines.range(input, evidence).text();
        if (raw.codePointCount(0, raw.length()) > 4096)
            throw new IllegalArgumentException("Experience field limit exceeded");
        return new Atom(
                raw,
                java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
                        .replaceAll("(?U)\\s+", " "),
                evidence);
    }
}
