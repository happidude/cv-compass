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
    private static final Pattern LEGAL_SUFFIX =
            Pattern.compile(
                    "(?iu).+\\b(?:ltd\\.?|limited|inc\\.?|corporation|corp\\.?|llc|jsc\\.?|co\\.)$");
    private static final Pattern GENERIC_EMPLOYER =
            Pattern.compile(
                    "(?iu)^(?:project[-–— ]based|freelance|remote|full[-–— ]time|part[-–— ]time|contract)\\.?$");
    private static final Pattern DESCRIPTIVE_EMPLOYER =
            Pattern.compile(
                    "(?iu)^(?:coordinate[ds]?|provided?|prepared?|created?|performed?|implemented?|organized?|assisted?|delivered?|conducted?|oversaw|supervised?|handled?)\\b.*");
    private static final Pattern DOT_SEPARATOR = Pattern.compile("\\.\\s+");
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
    private static final String LABEL_MODIFIER = "(?:current|last|previous|former|present|recent)";
    private static final Pattern LABEL_MODIFIER_WORD =
            Pattern.compile("(?iu)^" + LABEL_MODIFIER + "$");
    private static final Pattern LEADING_LABEL_MODIFIER =
            Pattern.compile("(?iu)^" + LABEL_MODIFIER + "\\s+");
    private static final Pattern EXPERIENCE_INTRO =
            Pattern.compile(
                    "(?iu)^(?:positions?\\s+and\\s+experiences?\\s+(?:in|at)|experience\\s+at)\\s+\\S.*[:：]$");
    private static final Pattern CONTEXT_LABEL_V34 =
            Pattern.compile("(?iu)^(?:location|category|report\\s+to|industry|địa chỉ)\\s*[:：].*");
    private static final Pattern BULLET_PREFIX = Pattern.compile("^(?:[-–—*•●▪]|\\d+[.)])\\s*");
    private static final Pattern TITLE_SENTENCE =
            Pattern.compile("(?iu)^work(?:ing)?\\s+as\\s+(.+?)\\s+in\\s+\\S.*$");
    private static final Pattern APPLICATION =
            Pattern.compile(
                    "(?iu)^(?:application for|application to|applying for|ứng tuyển|position applied for)\\b.*");
    private static final Pattern MODIFIER =
            Pattern.compile(
                    "(?iu)(?:senior|junior|deputy|assistant|acting|regional|global|head of|chief|lead)");

    private static final Pattern COMPANY_PREFIX =
            Pattern.compile("(?iu)^(?:công ty|cty|ctcp|tnhh|tập đoàn)\\s+.+");
    private static final Pattern PIPE_SEPARATOR = Pattern.compile("\\s+\\|\\s+");
    private static final Pattern PRIMARY_SEPARATOR =
            Pattern.compile(PIPE_SEPARATOR.pattern() + "|\\s+/\\s+");
    private static final Pattern DASH_SEPARATOR = Pattern.compile("\\s+[-–—]\\s+");
    private static final Pattern TITLE_SEPARATOR =
            Pattern.compile(PRIMARY_SEPARATOR.pattern() + "|" + DASH_SEPARATOR.pattern());
    private static final Pattern STREET_ADDRESS =
            Pattern.compile(
                    "(?iu)^(?:\\d+/\\d+[\\p{L}\\d/]*\\s+[^,]+,|\\d+\\p{L}?\\s+[^,]+,\\s*(?:phường|quận|ward|district)\\b).*");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern STOP_HEADING_SUFFIX =
            Pattern.compile("(?iu)^(?:\\s+(?:and|và)(?=\\s|$)|\\s*[&/,])");
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

    record Atom(String raw, String text, List<Evidence> evidence, String originalTitle) {
        Atom(String raw, String text, List<Evidence> evidence) {
            this(raw, text, evidence, null);
        }

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

    private final ExperienceGrammarDates dates;
    private final boolean v34;

    ExperienceGrammarRows(
            Extraction input,
            ExperienceDefinition config,
            List<SourceLine> lines,
            ExperienceGrammarDates dates,
            boolean v34) {
        this.dates = dates;
        this.v34 = v34;
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
        if (CONTEXT.matcher(line.text).matches()
                || APPLICATION.matcher(line.text).matches()
                || v34
                        && (CONTEXT_LABEL_V34.matcher(withoutBullet(line.text)).matches()
                                || experienceIntro(line.text))
                || anchored && streetAddressWithoutLeadingPeriod(line.text))
            return new Row(line, null, List.of(), List.of(), index, false, false, true);
        if (anchored && !dates.hasRange(line.text) && TITLE_SENTENCE.matcher(line.text).matches()) {
            var value = titleValue(line, atom(line, 0, line.text.length()), 0);
            return new Row(line, null, List.of(value), List.of(), index, false, false, false);
        }
        var statement = STATEMENT.matcher(line.text);
        var statementPeriod = dates.find(line);
        if (statement.matches() && statementPeriod != null) {
            if (dates.isV34() && !companyTextAllowed(statement.group(1)))
                return new Row(line, null, List.of(), List.of(), index, false, true, false);
            return new Row(
                    line,
                    atom(line, statement.start(1), statement.end(1)),
                    List.of(),
                    List.of(statementPeriod.proposal()),
                    index,
                    false,
                    false,
                    false);
        }
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
                            && !heading(next.text, anchored)
                            && (!anchored || canContinueLabel(label, next))) {
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
                            dates.findAll(valueLine).stream()
                                    .filter(
                                            capture ->
                                                    capture.start() >= valueStart
                                                            && capture.end() <= valueEnd)
                                    .toList();
                    if (captures.isEmpty() || anchored && captures.size() == 1)
                        periods.add(dates.labelled(valueLine, valueStart, valueEnd));
                    else captures.forEach(capture -> periods.add(capture.proposal()));
                } else {
                    if (CONTEXT.matcher(value.text()).matches()) continue;
                    var captures = dates.findAll(valueLine);
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
                        if (dates.isV34() && !companyTextAllowed(value.text())) {
                            ambiguous = true;
                            continue;
                        }
                        var cleaned = stripContinuation(valueLine, value);
                        continued |= cleaned != value;
                        value = cleaned;
                        if (consumed + 1 < lines.size() && !company(value.text(), anchored)) {
                            var next = lines.get(consumed + 1);
                            if (next.page == line.page
                                    && company(value.text() + " " + next.text, anchored)
                                    && matches(next.text, anchored).isEmpty()
                                    && (!anchored || !dates.hasRange(next.text))) {
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
                                    && matches(next.text, anchored).isEmpty()
                                    && (!anchored || !dates.hasRange(next.text))) {
                                var spans = new ArrayList<>(value.evidence());
                                spans.add(next.whole());
                                value = atom(spans);
                                consumed++;
                            }
                        }
                        titles.add(
                                v34
                                        ? titleValue(
                                                valueLine,
                                                value,
                                                valueLine == line ? label.valueStart() : 0)
                                        : value);
                    }
                }
            }
        } else {
            if (anchored) {
                var headingRow = anchoredRow(index);
                if (headingRow != null) return headingRow;
            }
            var capture = dates.find(line);
            if (line.raw.matches("(?s).*\\S(?:\\t| {3,})\\S.*")
                    && !line.text.contains("|")
                    && capture != null
                    && !dates.dateOnly(line))
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
                        periods.add(dates.labelled(line, parts.start(), parts.end()));
                    else if (company(value.text(), anchored)) companies.add(value);
                    else if (title(value.text(), anchored)) titles.add(value);
                    else ambiguous = true;
                }
            } else if (capture != null) {
                periods.add(capture.proposal());
                if (!dates.dateOnly(line)) {
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
                periods.add(dates.labelled(line, 0, line.text.length()));
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
                        && title(lines.get(index + 1).text, anchored)
                        && (!anchored || !dates.hasRange(lines.get(index + 1).text))) {
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
        if (anchored
                && !continued
                && matches.isEmpty()
                && periods.isEmpty()
                && !companies.isEmpty()
                && !adjacentAnchor(index - 1, line.page)
                && !adjacentAnchor(consumed + 1, line.page)) companies.clear();
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
        return row != null
                        && row.company() != null
                        && !row.periods().isEmpty()
                        && !row.ambiguous()
                        && row.periods().stream()
                                .noneMatch(period -> period.state().equals("INVALID"))
                ? row
                : null;
    }

    boolean hasExplicitPeriod(SourceLine line) {
        return matches(line.text, true).stream().anyMatch(label -> label.field().equals("period"));
    }

    boolean hasExplicitLabel(SourceLine line) {
        return !matches(line.text, true).isEmpty();
    }

    boolean structuredMargin(SourceLine line) {
        return dates.hasRange(line.text)
                || !matches(line.text, true).isEmpty()
                || company(line.text, true)
                || title(line.text, true);
    }

    boolean adjacentTitle(int index) {
        var line = nearbyHeadingText(index, index);
        return line != null && title(line.text, true);
    }

    private boolean canContinueLabel(Match label, SourceLine line) {
        var range = dates.find(line);
        return range == null
                || label.field().equals("period")
                        && range.proposal().state().equals("PROPOSED")
                        && (dates.dateOnly(line) || wrappedPeriod(line));
    }

    private Row anchoredRow(int index) {
        var line = lines.get(index);
        var captures = dates.findAll(line);
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

        int start = dates.withoutLeadingRangeConnector(line, capture);
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
        if (dates.dateOnly(line) || start == 0 && line.text.substring(end).isBlank()) {
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

        start = trimAtomEnd(line.text, 0, start);
        end = trimAtomStart(line.text, end, line.text.length());
        var before = atom(line, 0, start);
        var after = atom(line, end, line.text.length());
        String remainder =
                ((before == null ? "" : before.text()) + " " + (after == null ? "" : after.text()))
                        .strip();
        boolean shortDotted =
                remainder.endsWith(".")
                        && WORD.matcher(remainder).results().limit(11).count() <= 10
                        && shortHeading(remainder.substring(0, remainder.length() - 1));
        if ((!shortHeading(remainder) && !shortDotted)
                || STREET_ADDRESS.matcher(remainder).matches()
                || BULLET.matcher(line.text).matches())
            return new Row(line, null, List.of(), List.of(), index, false, false, true);

        if (remainder.isEmpty())
            return new Row(
                    line, null, List.of(), List.of(capture.proposal()), index, false, false, false);
        var separator =
                hasPrimarySeparator(line, 0, start)
                                || hasPrimarySeparator(line, end, line.text.length())
                        ? PRIMARY_SEPARATOR
                        : DASH_SEPARATOR;
        var parts = new ArrayList<Atom>();
        var titles = new ArrayList<Atom>();
        datedHeadingParts(line, 0, start, separator, shortDotted, parts, titles);
        datedHeadingParts(line, end, line.text.length(), separator, shortDotted, parts, titles);
        boolean hasDottedTitle = !titles.isEmpty();
        var companies = new ArrayList<Atom>();
        for (var part : parts) {
            if (location(part.text())
                    || part == parts.getFirst()
                            && ExperienceDefinition.key(part.text()).equals("earlier career"))
                continue;
            if (!hasDottedTitle && title(part.text(), true)) titles.add(part);
            else if (companyTextAllowed(part.text())) companies.add(part);
        }
        boolean ownCompany =
                companies.size() == 1
                        && (hasDottedTitle || company(companies.getFirst().text(), true));
        var next =
                ownCompany
                                || companies.isEmpty() && titles.isEmpty()
                                || !companies.isEmpty()
                                        && standaloneAnchor(index + 2, line.page, true)
                        ? null
                        : followingCompany(index, index + 1, true);
        if (next != null) {
            var headingTitle = before == null ? after : before;
            if (before != null && after != null) {
                var evidence = new ArrayList<>(before.evidence());
                evidence.addAll(after.evidence());
                headingTitle = new Atom(before.raw() + " " + after.raw(), remainder, evidence);
            }
            return new Row(
                    line,
                    next,
                    List.of(headingTitle),
                    List.of(capture.proposal()),
                    index + 1,
                    false,
                    false,
                    false);
        }

        if (companies.size() > 1
                && separator == PRIMARY_SEPARATOR
                && hasOutsideSeparator(line, 0, start, DASH_SEPARATOR)) {
            var legal = companies.stream().filter(value -> legalName(value.text())).toList();
            if (legal.size() == 1) {
                companies.retainAll(legal);
            } else if (legal.isEmpty()
                    && after == null
                    && !titles.isEmpty()
                    && companies.getLast() == parts.getLast()) {
                var nearest = companies.getLast();
                companies.clear();
                companies.add(nearest);
            }
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

    private boolean wrappedPeriod(SourceLine line) {
        var capture = dates.find(line);
        return capture != null
                && line.text.substring(0, capture.start()).strip().equals("(")
                && line.text.substring(capture.end()).strip().equals(")");
    }

    private Atom followingCompany(int anchor, int index) {
        return followingCompany(anchor, index, false);
    }

    private Atom followingCompany(int anchor, int index, boolean requireCompanyLike) {
        var next = nearbyHeadingText(anchor, index);
        if (next == null
                || !companyTextAllowed(next.text)
                || index > anchor && standaloneAnchor(index + 1, next.page, false)) return null;
        var separator =
                hasPrimarySeparator(next, 0, next.text.length())
                        ? PRIMARY_SEPARATOR
                        : DASH_SEPARATOR;
        var parts = new ArrayList<Atom>();
        headingParts(next, 0, next.text.length(), separator, parts);
        var values = parts.stream().filter(part -> !location(part.text())).toList();
        if (values.isEmpty()
                || values.size() != 1 && (!requireCompanyLike || separator == PRIMARY_SEPARATOR))
            return null;
        var value = values.size() == 1 ? values.getFirst() : atom(next, 0, next.text.length());
        if (title(value.text(), true)) return null;
        if (!requireCompanyLike) return value;

        boolean followedByRegion =
                parts.size() == 2
                        && parts.getFirst() == value
                        && location(parts.getLast().text())
                        && hasOutsideSeparator(next, 0, next.text.length(), PIPE_SEPARATOR);
        long letters = value.text().codePoints().filter(Character::isLetter).count();
        long uppercase = value.text().codePoints().filter(Character::isUpperCase).count();
        return followedByRegion || letters > 0 && uppercase * 5 >= letters * 3 ? value : null;
    }

    private boolean standaloneAnchor(int index, int page, boolean includeTitle) {
        if (index >= lines.size()) return false;
        var line = lines.get(index);
        if (line.page != page || heading(line.text, true)) return false;
        if (dates.dateOnly(line) || wrappedPeriod(line)) return true;
        var rowLabels = matches(line.text, true);
        if (rowLabels.stream().anyMatch(label -> label.field().equals("company"))) return false;
        return rowLabels.stream()
                        .anyMatch(
                                label ->
                                        label.field().equals("period")
                                                || includeTitle && label.field().equals("title"))
                || includeTitle
                        && rowLabels.isEmpty()
                        && !dates.hasRange(line.text)
                        && title(line.text, true);
    }

    private boolean adjacentAnchor(int index, int page) {
        if (index < 0 || index >= lines.size()) return false;
        var neighbor = lines.get(index);
        if (neighbor.page != page
                || heading(neighbor.text, true)
                || streetAddressWithoutLeadingPeriod(neighbor.text)) return false;
        if (matches(neighbor.text, true).stream()
                .anyMatch(label -> label.field().equals("period") || label.field().equals("title")))
            return true;
        return title(neighbor.text, true)
                || shortHeading(neighbor.text)
                        && (neighbor.text.matches("[0-9]{4}") || dates.find(neighbor) != null);
    }

    private SourceLine nearbyHeadingText(int anchor, int index) {
        if (index >= lines.size()) return null;
        var next = lines.get(index);
        return next.page == lines.get(anchor).page
                        && shortHeading(next.text)
                        && !heading(next.text, true)
                        && matches(next.text, true).isEmpty()
                        && !next.text.matches("[0-9]{4}")
                        && !dates.hasRange(next.text)
                ? next
                : null;
    }

    private static boolean hasPrimarySeparator(SourceLine line, int start, int end) {
        return hasOutsideSeparator(line, start, end, PRIMARY_SEPARATOR);
    }

    private static boolean hasOutsideSeparator(
            SourceLine line, int start, int end, Pattern separator) {
        return separator
                .matcher(line.text)
                .region(start, end)
                .results()
                .anyMatch(match -> !insideParentheses(line.text, match.start()));
    }

    private void headingParts(
            SourceLine line, int start, int end, Pattern separator, List<Atom> parts) {
        var separators = separator.matcher(line.text).region(start, end);
        while (separators.find()) {
            if (insideParentheses(line.text, separators.start())) continue;
            addHeadingPart(line, start, separators.start(), separator, parts);
            start = separators.end();
        }
        addHeadingPart(line, start, end, separator, parts);
    }

    private void addHeadingPart(
            SourceLine line, int start, int end, Pattern separator, List<Atom> parts) {
        var part = atom(line, start, end);
        if (part == null) return;
        if (separator == PRIMARY_SEPARATOR
                && hasOutsideSeparator(line, start, end, DASH_SEPARATOR)) {
            var pair = new ArrayList<Atom>();
            headingParts(line, start, end, DASH_SEPARATOR, pair);
            if (pair.size() == 2
                    && (legalName(pair.getFirst().text()) && title(pair.getLast().text(), true)
                            || title(pair.getFirst().text(), true)
                                    && legalName(pair.getLast().text()))) {
                parts.addAll(pair);
                return;
            }
        }
        parts.add(part);
    }

    private void datedHeadingParts(
            SourceLine line,
            int start,
            int end,
            Pattern separator,
            boolean shortDotted,
            List<Atom> parts,
            List<Atom> titles) {
        if (shortDotted) {
            var split = DOT_SEPARATOR.matcher(line.text).region(start, end);
            Atom selectedCompany = null;
            Atom selectedTitle = null;
            while (split.find()) {
                if (insideParentheses(line.text, split.start())
                        || hasPrimarySeparator(line, start, end)
                        || hasOutsideSeparator(line, start, end, DASH_SEPARATOR)) continue;
                var employer = atom(line, start, split.start() + 1);
                var role = atom(line, split.end(), end);
                if (employer == null || role == null || !role.text().endsWith(".")) continue;
                var title = atom(line, split.end(), end - 1);
                boolean legal = LEGAL_SUFFIX.matcher(employer.text()).matches();
                boolean uppercase =
                        employer.text().codePoints().anyMatch(Character::isUpperCase)
                                && employer.text().codePoints().noneMatch(Character::isLowerCase);
                if (title != null
                        && shortHeading(title.text())
                        && Character.isLetter(title.text().codePointAt(0))
                        && (legal
                                || uppercase
                                        && title.text()
                                                .codePoints()
                                                .anyMatch(Character::isLowerCase))) {
                    selectedCompany = legal ? employer : atom(line, start, split.start());
                    selectedTitle = title;
                }
            }
            if (selectedCompany != null) {
                parts.add(selectedCompany);
                titles.add(selectedTitle);
                return;
            }
            var whole = atom(line, start, end);
            if (whole != null
                    && whole.text().endsWith(".")
                    && !LEGAL_SUFFIX.matcher(whole.text()).matches()
                    && title(whole.text().substring(0, whole.text().length() - 1), true)) {
                parts.add(atom(line, start, end - 1));
                return;
            }
        }
        headingParts(line, start, end, separator, parts);
    }

    private static boolean shortHeading(String text) {
        return text.length() <= 160
                && text.split(" ").length <= 14
                && !text.endsWith(".")
                && !PROSE.matcher(text).matches()
                && !GERUND_PROSE.matcher(text).matches()
                && !BULLET.matcher(text).matches()
                && !CONTEXT.matcher(text).matches()
                && !CONTEXT_LABEL_V34.matcher(withoutBullet(text)).matches()
                && !experienceIntro(text)
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

    private boolean streetAddressWithoutLeadingPeriod(String text) {
        return STREET_ADDRESS.matcher(text).matches() && !dates.startsWithRange(text);
    }

    private static boolean legalName(String text) {
        return LEGAL_SUFFIX.matcher(outsideParentheses(text)).matches();
    }

    private static String outsideParentheses(String text) {
        return text.replaceAll("\\([^()]*\\)", "").strip();
    }

    private static boolean companyTextAllowed(String text) {
        String outside = outsideParentheses(text);
        boolean longText = WORD.matcher(outside).results().limit(9).count() > 8;
        return !STREET_ADDRESS.matcher(text).matches()
                && !GENERIC_EMPLOYER.matcher(outside).matches()
                && !(longText
                        && !outside.isEmpty()
                        && (Character.isLowerCase(outside.codePointAt(0))
                                || PROSE.matcher(outside).matches()
                                || GERUND_PROSE.matcher(outside).matches()
                                || DESCRIPTIVE_EMPLOYER.matcher(outside).matches()))
                && (!outside.contains(",")
                        || outside.split(" ").length <= 8
                        || LEGAL_SUFFIX.matcher(outside).matches());
    }

    static String limitedEmployerKey(String text) {
        return ExperienceDefinition.key(
                text.replaceFirst("(?iu)\\s+(?:co\\.?[,]?\\s+)?ltd\\.?$", " ltd"));
    }

    private boolean company(String text, boolean v34) {
        if (v34 && !companyTextAllowed(text)) return false;
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
                                || v34
                                        && (COMPANY_PREFIX.matcher(text).matches()
                                                || LEGAL_SUFFIX
                                                        .matcher(companyText.strip())
                                                        .matches())
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

    private boolean heading(String text, boolean v34) {
        String key = ExperienceDefinition.key(text.replaceFirst("[:：]$", ""));
        return java.util.stream.Stream.of(
                                config.rules().experienceHeadings(),
                                config.rules().summaryHeadings())
                        .flatMap(List::stream)
                        .anyMatch(value -> ExperienceDefinition.key(value).equals(key))
                || stopHeading(text, v34)
                || v34 && (contextHeading(key, true) || flexibleSection(text) != null);
    }

    static String flexibleSection(String text) {
        String key = ExperienceDefinition.key(text.replaceFirst("[:：]$", ""));
        if (experienceIntro(text)
                || key.matches("^position and experiences? in .*")
                || text.endsWith(".")
                || BULLET.matcher(text).matches()
                || WORD.matcher(key).results().limit(7).count() > 6
                || key.codePoints().anyMatch(Character::isDigit)
                || !key.matches(
                        "(?u).*(?:\\bexperiences?\\b|\\bemployment\\b|\\bwork history\\b|\\bcareer history\\b|kinh nghiệm|quá trình công tác).*"))
            return null;
        return key.matches("(?u).*(?:\\bsummary\\b|\\boverview\\b|tóm tắt).*")
                ? "SUMMARY"
                : "DETAIL";
    }

    boolean stopHeading(String text, boolean v34) {
        String key = ExperienceDefinition.key(text.replaceFirst("[:：]$", ""));
        for (var heading : config.rules().stopHeadings()) {
            var stop = ExperienceDefinition.key(heading);
            if (stop.equals(key)) return true;
            if (!v34 || !key.startsWith(stop)) continue;
            var connector = STOP_HEADING_SUFFIX.matcher(key).region(stop.length(), key.length());
            if (connector.lookingAt()
                    && !Character.isLowerCase(text.stripLeading().codePointAt(0))
                    && !text.stripTrailing().endsWith(".")
                    && key.indexOf(',', connector.end()) < 0
                    && WORD.matcher(text).results().limit(7).count() <= 6
                    && !dates.hasRange(key)) return true;
        }
        return false;
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
                if ((v34 || this.v34) && insideParentheses(text, matcher.start())
                        || this.v34 && !atLabelBoundary(text, matcher.start())) continue;
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

    private boolean atLabelBoundary(String text, int start) {
        String prefix = withoutBullet(text.substring(0, start)).strip();
        if (prefix.isEmpty() || LABEL_MODIFIER_WORD.matcher(prefix).matches()) return true;
        // Preserve explicit pipe-delimited fields after a leading label.
        if (!prefix.endsWith("|")) return false;
        String first = LEADING_LABEL_MODIFIER.matcher(withoutBullet(text)).replaceFirst("");
        return labels.stream().anyMatch(label -> label.pattern().matcher(first).lookingAt());
    }

    static boolean experienceIntro(String text) {
        return EXPERIENCE_INTRO.matcher(withoutBullet(text)).matches();
    }

    private static String withoutBullet(String text) {
        return BULLET_PREFIX.matcher(text).replaceFirst("");
    }

    private Atom titleValue(SourceLine line, Atom value, int searchStart) {
        var sentence = TITLE_SENTENCE.matcher(value.text());
        if (!sentence.matches()) return value;
        int start = line.text.indexOf(value.text(), searchStart);
        if (start < 0) return value;
        var title = atom(line, start + sentence.start(1), start + sentence.end(1));
        if (title == null) return value;
        var evidence = new ArrayList<>(title.evidence());
        evidence.addAll(value.evidence());
        return new Atom(title.raw(), title.text(), evidence, value.raw());
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
        start = trimAtomStart(line.text, start, end);
        end = trimAtomEnd(line.text, start, end);
        return start == end ? null : atom(List.of(line.span(start, end)));
    }

    private static int trimAtomStart(String text, int start, int end) {
        while (start < end && atomBoundary(text.charAt(start))) start++;
        return start;
    }

    private static int trimAtomEnd(String text, int start, int end) {
        while (end > start && atomBoundary(text.charAt(end - 1))) end--;
        return end;
    }

    private static boolean atomBoundary(char value) {
        return Character.isWhitespace(value) || "|/:：".indexOf(value) >= 0;
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
