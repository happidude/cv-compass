package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PeriodProposal;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Typed finite row anchors establish relationships; whitespace columns do not. */
final class ExperienceGrammarRows {
    private static final Pattern COMPANY =
            Pattern.compile(
                    "(?iu).+\\b(?:ltd\\.?|limited|inc\\.?|corporation|corp\\.?|company|group|llc|jsc|co\\.?)(?:\\s*\\([^)]{1,80}\\))?$");
    private static final Pattern ROLE =
            Pattern.compile(
                    "(?iu)\\b(?:engineer|manager|director|developer|analyst|officer|accountant|auditor|supervisor|consultant|specialist|president|CEO|CFO|COO|founder|co-founder|kỹ sư|giám đốc|trưởng phòng|chuyên viên)\\b");
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

    Row parse(int index) {
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
        var matches = matches(line.text);
        if (!matches.isEmpty()) {
            for (int n = 0; n < matches.size(); n++) {
                var label = matches.get(n);
                int end = n + 1 < matches.size() ? matches.get(n + 1).start() : line.text.length();
                Atom value = atom(line, label.valueStart(), end);
                SourceLine valueLine = line;
                if (value == null && index + 1 < lines.size()) {
                    var next = lines.get(index + 1);
                    if (next.page == line.page
                            && matches(next.text).isEmpty()
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
                        if (consumed + 1 < lines.size() && !company(value.text())) {
                            var next = lines.get(consumed + 1);
                            if (next.page == line.page
                                    && company(value.text() + " " + next.text)
                                    && matches(next.text).isEmpty()) {
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
                                    && title(next.text)
                                    && matches(next.text).isEmpty()) {
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
                    else if (company(value.text())) companies.add(value);
                    else if (title(value.text())) titles.add(value);
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
                        if (company(value.text())) companies.add(value);
                        else if (title(value.text())) titles.add(value);
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
                if (company(cleaned.text())) companies.add(cleaned);
                else if (title(value.text())) titles.add(value);
                else if (MODIFIER.matcher(value.text()).matches()
                        && index + 1 < lines.size()
                        && lines.get(index + 1).page == line.page
                        && title(lines.get(index + 1).text)) {
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

    boolean explicitEmployment(SourceLine line) {
        return matches(line.text).stream().anyMatch(label -> label.field().equals("company"))
                || STATEMENT.matcher(line.text).matches();
    }

    private static Atom empty() {
        return new Atom("", "", List.of());
    }

    boolean company(String text) {
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
        return configured
                || !PROSE.matcher(text).matches()
                        && !CONTEXT.matcher(text).matches()
                        && !text.contains(":")
                        && (COMPANY.matcher(text).matches()
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

    private boolean title(String text) {
        if (company(text)
                || PROSE.matcher(text).matches()
                || CONTEXT.matcher(text).matches()
                || text.endsWith(".")
                || text.length() > 160
                || text.split(" ").length > 14
                || text.contains(":")
                || text.matches("(?iu).*(?:CEO|CFO|COO).*(?:course|training|program).*"))
            return false;
        return ROLE.matcher(text).find()
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

    private List<Match> matches(String text) {
        var result = new ArrayList<Match>();
        for (var label : labels) {
            var matcher = label.pattern().matcher(text);
            while (matcher.find()) {
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
