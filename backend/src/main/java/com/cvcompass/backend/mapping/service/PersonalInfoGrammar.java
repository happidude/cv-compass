package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Issue;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.Alternative;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.PersonalField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bounded field/block grammar for the opt-in engine; it never infers missing layout. */
final class PersonalInfoGrammar {
    private static final int MAX_PENDING_LINES = 24;
    private static final int MAX_VALUE_LINES = 6;
    private static final int MAX_HEADER_LINES = 32;
    private static final int MAX_HEADER_CODE_POINTS = 4096;
    private static final Pattern EMAIL =
            Pattern.compile("[\\p{L}\\p{N}._%+'’-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
    private static final Pattern LOCATION_BOUNDARY =
            Pattern.compile("^(?:[•●▪*–-]|\\p{L}[^:：]*[:：])");
    private static final Pattern CONTACT_SEGMENT = Pattern.compile("[^|•;]+");
    private static final Pattern UNLABELED_CONTACT_SEGMENT = Pattern.compile("[^|•●▪;,]+");
    private static final Pattern YEAR_SEQUENCE =
            Pattern.compile("(?:19|20)[0-9]{2}(?:[ /–-]+(?:19|20)[0-9]{2})*[ /–-]*");
    private static final Set<String> PROTECTED_REGIONS =
            Set.of("REFERENCE", "RECRUITER", "COMPANY");
    private static final List<String> CONTACT_FIELDS = List.of("email", "phone");
    private static final Pattern INTRO =
            Pattern.compile(
                    "(?iu)^(?:I am|I have|I bring|I offer|My background|My experience|Tôi|Với hơn)\\b.*");

    private record Candidate(
            String raw, String logical, List<Evidence> evidence, boolean uncertain, String issue) {}

    private record Value(PersonalInfoGrammarLines.Line line, Evidence evidence) {}

    private record Contact(String field, String logical, Evidence evidence) {}

    private final Extraction input;
    private final boolean v32;
    private final PersonalInfoDefinition config;
    private final PersonalInfoGrammarLabels labels;
    private final List<PersonalInfoGrammarLines.Line> lines;
    private final Map<String, List<Candidate>> candidates = new TreeMap<>();
    private final List<Candidate> summarySections = new ArrayList<>();
    private final Set<String> unlabeledFields = new HashSet<>();
    private final Set<Integer> summaryConsumed = new HashSet<>();
    private final Set<Integer> assignedValues = new HashSet<>();

    PersonalInfoGrammar(Extraction input, PersonalInfoDefinition config) {
        this.input = input;
        this.config = config;
        this.v32 =
                PersonalInfoDefinition.GRAMMAR_V32_ENGINE_VERSION.equals(
                        config.engineCompatibility());
        this.labels = new PersonalInfoGrammarLabels(config);
        this.lines = PersonalInfoGrammarLines.read(input, config);
        PersonalInfoDefinition.FIELD_NAMES.forEach(
                field -> candidates.put(field, new ArrayList<>()));
    }

    Map<String, PersonalField> parse() {
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (!line.personal() || line.blank() || line.noise()) continue;
            var found = labels.find(line.text());
            if (!summaryConsumed.contains(index)
                    && line.heading()
                    && line.region().equals("SUMMARY")) {
                summary(
                        index,
                        found.stream()
                                .filter(label -> "summary".equals(label.field()))
                                .findFirst()
                                .orElse(null));
            }
            for (var label : found) {
                if (label.field() == null || !enabled(label.field(), "EXPLICIT_LABEL")) continue;
                if (label.field().equals("summary")) {
                    if (!summaryConsumed.contains(index)) summary(index, label);
                    continue;
                }
                Evidence span = span(line, label.valueStart(), label.end());
                if (span == null) pending(index);
                else extended(label.field(), new Value(line, span));
            }
            if (found.isEmpty() && !line.heading() && !line.region().equals("SUMMARY"))
                typedContact(line);
            if (!summaryConsumed.contains(index)
                    && !line.heading()
                    && line.region().equals("PERSONAL")
                    && INTRO.matcher(line.text()).matches()
                    && line.text().split(" ").length >= 6
                    && enabled("summary", "SECTION_CONTEXT")) summary(index, null);
        }
        if (v32) {
            mergeSummarySections();
            unlabeledContacts();
        }
        headerNames();
        var result = new TreeMap<String, PersonalField>();
        for (String field : PersonalInfoDefinition.FIELD_NAMES)
            result.put(field, select(field, candidates.get(field)));
        return result;
    }

    private boolean enabled(String field, String priority) {
        var rule = config.personalFields().get(field);
        return rule.enabled() && rule.priorities().contains(priority);
    }

    private void pending(int start) {
        var pending = new java.util.LinkedHashSet<String>();
        var values = new ArrayList<Value>();
        boolean unknownLabel = false;
        for (int i = start; i < lines.size() && i <= start + MAX_PENDING_LINES; i++) {
            var line = lines.get(i);
            if (line.page() != lines.get(start).page()
                    || !line.personal()
                    || (i > start && line.heading())) break;
            if (line.blank() || line.text().matches("[:：|]")) continue;
            if (!values.isEmpty() && pending.contains("location") && locationBoundary(line)) break;
            var found = labels.find(line.text());
            if (!found.isEmpty()) {
                if (!values.isEmpty()
                        || found.stream()
                                .anyMatch(
                                        label ->
                                                span(line, label.valueStart(), label.end())
                                                        != null)) break;
                for (var label : found) {
                    if (label.field() == null) unknownLabel = true;
                    else pending.add(label.field());
                }
                continue;
            }
            if (line.noise()) continue;
            values.add(new Value(line, line.source().whole()));
            if (values.size() >= Math.max(MAX_VALUE_LINES, pending.size())) break;
        }
        if (pending.size() == 1 && !unknownLabel && !values.isEmpty()) {
            // A boundary can also be the first apparent value of a detached address label.
            if (pending.contains("location") && locationBoundary(values.getFirst().line())) return;
            if (pending.contains("fullName")
                    && values.size() > 1
                    && check(
                                            "fullName",
                                            values.get(0).evidence().text(),
                                            values.get(0).evidence().text())
                                    .normalized()
                            != null
                    && check(
                                            "fullName",
                                            values.get(1).evidence().text(),
                                            values.get(1).evidence().text())
                                    .normalized()
                            != null) {
                for (var value : values) {
                    add(
                            "fullName",
                            List.of(value.evidence()),
                            value.evidence().text(),
                            true,
                            "LABEL_VALUE_UNRESOLVED");
                    assignedValues.add(value.line().index());
                }
                return;
            }
            extended(pending.iterator().next(), values.getFirst());
            return;
        }
        for (String field : pending) {
            if (!enabled(field, "EXPLICIT_LABEL")) continue;
            for (var value : values) {
                var checked = check(field, value.evidence().text(), value.evidence().text());
                boolean typed =
                        List.of("email", "phone", "gender").contains(field)
                                && checked.normalized() != null;
                if (typed || !List.of("email", "phone", "gender").contains(field)) {
                    add(
                            field,
                            List.of(value.evidence()),
                            value.evidence().text(),
                            !typed,
                            typed ? null : "LABEL_VALUE_UNRESOLVED");
                    assignedValues.add(value.line().index());
                }
            }
        }
    }

    private void extended(String field, Value value) {
        var evidence = new ArrayList<Evidence>();
        evidence.add(value.evidence());
        String logical = value.evidence().text();
        int next = nextMeaningful(value.line().index() + 1, value.line().page(), 8);
        if (List.of("email", "phone").contains(field) && next >= 0) {
            var line = lines.get(next);
            String continuation = line.text();
            boolean email =
                    field.equals("email")
                            && logical.matches("(?s).+[@.]$")
                            && continuation.matches("[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");
            boolean phone =
                    field.equals("phone")
                            && logical.matches("(?s).+[0-9][- ]$")
                            && continuation.matches("[0-9 .-]+");
            if (email || phone) {
                evidence.add(line.source().whole());
                logical += line.source().whole().text();
                assignedValues.add(next);
            }
        }
        if (field.equals("fullName")
                && PersonalInfoGrammarValidation.nameFragment(logical.strip())) {
            while (next >= 0
                    && evidence.size() < 3
                    && PersonalInfoGrammarValidation.nameFragment(lines.get(next).text())) {
                var line = lines.get(next);
                evidence.add(line.source().whole());
                assignedValues.add(next);
                logical += "\n" + line.source().whole().text();
                next = nextMeaningful(next + 1, value.line().page(), 2);
            }
        }
        if (field.equals("location")) {
            for (int i = value.line().index() + 1;
                    i < lines.size() && i <= value.line().index() + MAX_VALUE_LINES;
                    i++) {
                var line = lines.get(i);
                if (line.page() != value.line().page()
                        || line.blank()
                        || line.noise()
                        || line.heading()
                        || !line.personal()
                        || labels.isBoundary(line.text())
                        || locationBoundary(line)
                        || line.text().contains("@")
                        || line.text().matches("(?iu).*(?:https?://|www\\.).*")) break;
                evidence.add(line.source().whole());
                assignedValues.add(i);
            }
            logical = raw(evidence);
        }
        assignedValues.add(value.line().index());
        add(field, evidence, logical, value.line().warning(), null);
    }

    private boolean locationBoundary(PersonalInfoGrammarLines.Line line) {
        return v32 && LOCATION_BOUNDARY.matcher(line.text()).find();
    }

    private int nextMeaningful(int start, int page, int bound) {
        for (int i = start; i < lines.size() && i < start + bound; i++) {
            var line = lines.get(i);
            if (line.page() != page
                    || line.heading()
                    || !line.personal()
                    || labels.isBoundary(line.text())) return -1;
            if (!line.blank() && !line.noise() && !line.text().matches("[:：|]")) return i;
        }
        return -1;
    }

    private void typedContact(PersonalInfoGrammarLines.Line line) {
        if (!List.of("CONTACT", "PERSONAL").contains(line.region())) return;
        scanContacts(
                line,
                CONTACT_SEGMENT,
                enabled("phone", "SECTION_CONTEXT"),
                contact -> {
                    String field = contact.field();
                    if (!enabled(field, "SECTION_CONTEXT")) return;
                    if (field.equals("phone")) {
                        var checked = check(field, contact.logical(), contact.logical());
                        if (checked.normalized() == null
                                && !"PHONE_TRUNK_NOTATION_AMBIGUOUS".equals(checked.issue()))
                            return;
                    }
                    add(
                            field,
                            List.of(contact.evidence()),
                            contact.logical(),
                            line.warning(),
                            null);
                });
    }

    private void unlabeledContacts() {
        var missing =
                CONTACT_FIELDS.stream()
                        .filter(
                                field ->
                                        enabled(field, "SECTION_CONTEXT")
                                                && candidates.get(field).isEmpty())
                        .toList();
        if (missing.isEmpty()) return;
        for (var line : lines) {
            if (line.blank() || line.noise() || PROTECTED_REGIONS.contains(line.region())) continue;
            scanContacts(
                    line,
                    UNLABELED_CONTACT_SEGMENT,
                    missing.contains("phone"),
                    contact -> {
                        String field = contact.field();
                        if (missing.contains(field)
                                && !(field.equals("phone")
                                        && YEAR_SEQUENCE.matcher(contact.logical()).matches())
                                && check(field, contact.evidence().text(), contact.logical())
                                                .normalized()
                                        != null)
                            add(field, List.of(contact.evidence()), contact.logical(), false, null);
                    });
        }
        for (String field : missing) {
            var values = candidates.get(field);
            long distinct =
                    values.stream()
                            .map(value -> check(field, value.raw(), value.logical()).normalized())
                            .distinct()
                            .limit(2)
                            .count();
            if (distinct != 1) continue;
            if (values.stream().anyMatch(value -> value.evidence().getFirst().page() == 1))
                unlabeledFields.add(field);
            else values.clear();
        }
    }

    private void scanContacts(
            PersonalInfoGrammarLines.Line line,
            Pattern segments,
            boolean scanPhone,
            Consumer<Contact> accept) {
        var email = EMAIL.matcher(line.text());
        var withoutEmail = new StringBuilder(line.text());
        while (email.find()) {
            var evidence = line.source().span(email.start(), email.end());
            accept.accept(new Contact("email", evidence.text(), evidence));
            withoutEmail.replace(
                    email.start(),
                    email.end(),
                    (v32 ? "|" : " ").repeat(email.end() - email.start()));
        }
        if (!scanPhone) return;
        var delimiters = segments.matcher(withoutEmail);
        while (delimiters.find()) {
            int from = delimiters.start(), to = delimiters.end();
            while (from < to
                    && !Character.isDigit(withoutEmail.charAt(from))
                    && withoutEmail.charAt(from) != '+'
                    && withoutEmail.charAt(from) != '(') {
                char ch = withoutEmail.charAt(from);
                if (Character.isLetter(ch)) break;
                from++;
            }
            while (to > from && Character.isWhitespace(line.text().charAt(to - 1))) to--;
            if (from == to) continue;
            String value = withoutEmail.substring(from, to).strip();
            accept.accept(new Contact("phone", value, line.source().span(from, to)));
        }
    }

    private void summary(int start, PersonalInfoGrammarLabels.Label label) {
        if (!config.personalFields().get("summary").enabled()) return;
        var evidence = new ArrayList<Evidence>();
        boolean uncertain = false, bounded = false;
        int page = lines.get(start).page();
        var first = lines.get(start);
        if (label != null) {
            Evidence span = span(first, label.valueStart(), label.end());
            if (span != null) evidence.add(span);
        } else if (!first.heading()) evidence.add(first.source().whole());
        summaryConsumed.add(start);
        for (int i = start + 1; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.page() != page) {
                if (line.blank() || line.noise()) {
                    summaryConsumed.add(i);
                    continue;
                }
                if (line.page() == page + 1
                        && line.continued()
                        && line.heading()
                        && line.region().equals("SUMMARY")) {
                    page = line.page();
                    summaryConsumed.add(i);
                    continue;
                }
                if (line.heading()
                        && (!line.region().equals("SUMMARY") || (v32 && !line.continued()))) {
                    bounded = true;
                    break;
                }
                uncertain = true;
                break;
            }
            if (line.heading() || labels.isBoundary(line.text()) || !line.personal()) {
                bounded = true;
                break;
            }
            summaryConsumed.add(i);
            if (line.blank() || line.noise() || line.repeatedMargin()) continue;
            if (line.text().matches("[-•]\\s*[\\p{L}+#.]{1,12}")) uncertain = true;
            evidence.add(line.source().whole());
            uncertain |= line.warning() || line.unprovenSpacing();
        }
        if (evidence.isEmpty()) return;
        int last = evidence.getLast().page();
        if (!bounded && input.pages().stream().anyMatch(value -> value.number() > last))
            uncertain = true;
        String raw = raw(evidence);
        var candidate =
                new Candidate(
                        raw,
                        raw,
                        List.copyOf(evidence),
                        uncertain,
                        uncertain ? "SUMMARY_CONTINUATION_UNVERIFIED" : null);
        add("summary", candidate);
        if (v32 && (label != null || first.heading())) summarySections.add(candidate);
    }

    private void mergeSummarySections() {
        if (summarySections.size() < 2) return;
        String raw = String.join("\n\n", summarySections.stream().map(Candidate::raw).toList());
        var evidence =
                summarySections.stream().flatMap(section -> section.evidence().stream()).toList();
        boolean uncertain = summarySections.stream().anyMatch(Candidate::uncertain);
        candidates.get("summary").clear();
        add("summary", new Candidate(raw, raw, evidence, uncertain, "SUMMARY_MULTI_SECTION"));
    }

    private void headerNames() {
        if (!enabled("fullName", "PROFILE_HEADER")) return;
        var contexts = headerContexts();
        for (var line : lines) {
            var context = contexts.get(line.scope());
            if (context == null
                    || context.exceeded
                    || (!line.explicitPersonal() && !context.contact)) continue;
            if (!line.personal()
                    || !line.region().equals("PERSONAL")
                    || line.heading()
                    || line.noise()
                    || line.blank()
                    || summaryConsumed.contains(line.index())
                    || assignedValues.contains(line.index())
                    || labels.isBoundary(line.text())
                    || line.text().contains("@")
                    || INTRO.matcher(line.text()).matches()) continue;
            var evidence = new ArrayList<Evidence>();
            evidence.add(line.source().whole());
            String logical = line.source().whole().text();
            if (PersonalInfoGrammarValidation.nameFragment(line.text())) {
                int next = nextMeaningful(line.index() + 1, line.page(), 2);
                while (next >= 0
                        && evidence.size() < 3
                        && PersonalInfoGrammarValidation.nameFragment(lines.get(next).text())) {
                    evidence.add(lines.get(next).source().whole());
                    logical += "\n" + lines.get(next).source().whole().text();
                    assignedValues.add(next);
                    next = nextMeaningful(next + 1, line.page(), 2);
                }
            }
            var checked = PersonalInfoGrammarValidation.check("fullName", raw(evidence), logical);
            if (checked.normalized() != null && PersonalInfoGrammarValidation.headerName(logical))
                add("fullName", evidence, logical, line.warning() || line.unprovenSpacing(), null);
        }
    }

    private Map<Integer, HeaderContext> headerContexts() {
        var result = new java.util.HashMap<Integer, HeaderContext>();
        for (var line : lines) {
            if (!List.of("PERSONAL", "CONTACT").contains(line.region())) continue;
            var context = result.computeIfAbsent(line.scope(), ignored -> new HeaderContext());
            if (context.exceeded) continue;
            if (!line.blank()) {
                context.meaningful++;
                context.points += line.text().codePointCount(0, line.text().length());
            }
            if (context.meaningful > MAX_HEADER_LINES || context.points > MAX_HEADER_CODE_POINTS) {
                context.exceeded = true;
                continue;
            }
            if (!context.contact)
                context.contact =
                        line.text().contains("@")
                                || labels.find(line.text()).stream()
                                        .anyMatch(
                                                value ->
                                                        value.field() != null
                                                                && List.of(
                                                                                "email",
                                                                                "phone",
                                                                                "gender",
                                                                                "location")
                                                                        .contains(value.field()));
        }
        return result;
    }

    private static final class HeaderContext {
        int meaningful, points;
        boolean exceeded, contact;
    }

    private void add(
            String field,
            List<Evidence> evidence,
            String logical,
            boolean uncertain,
            String issue) {
        if (evidence.isEmpty() || !config.personalFields().get(field).enabled()) return;
        add(field, new Candidate(raw(evidence), logical, List.copyOf(evidence), uncertain, issue));
    }

    private void add(String field, Candidate candidate) {
        var values = candidates.get(field);
        if (values.size() >= 128)
            throw new IllegalArgumentException("Personal candidate limit exceeded");
        values.add(candidate);
    }

    private String raw(List<Evidence> evidence) {
        var result = new StringBuilder();
        Evidence previous = null;
        for (var span : evidence) {
            if (previous != null) {
                String gap = "\n";
                if (previous.page() == span.page()) {
                    if (previous.end() > span.start())
                        throw new IllegalArgumentException("Overlapping personal source spans");
                    String source =
                            input.pages().stream()
                                    .filter(page -> page.number() == span.page())
                                    .findFirst()
                                    .orElseThrow()
                                    .rawText();
                    String between =
                            source.substring(
                                    source.offsetByCodePoints(0, previous.end()),
                                    source.offsetByCodePoints(0, span.start()));
                    // A skipped page/header line is not part of the selected field. Only whitespace
                    // gaps may be retained without adding another evidence span.
                    if (between.codePoints()
                            .allMatch(
                                    cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)))
                        gap = between;
                }
                result.append(gap);
            }
            result.append(span.text());
            previous = span;
        }
        return result.toString();
    }

    private static Evidence span(PersonalInfoGrammarLines.Line line, int from, int to) {
        while (from < to
                && (Character.isWhitespace(line.text().charAt(from))
                        || "|;".indexOf(line.text().charAt(from)) >= 0)) from++;
        while (to > from
                && (Character.isWhitespace(line.text().charAt(to - 1))
                        || "|;".indexOf(line.text().charAt(to - 1)) >= 0)) to--;
        return from == to ? null : line.source().span(from, to);
    }

    private PersonalInfoGrammarValidation.Checked check(String field, String raw, String logical) {
        return PersonalInfoGrammarValidation.check(field, raw, logical, v32);
    }

    private PersonalField select(String field, List<Candidate> values) {
        if (!config.personalFields().get(field).enabled())
            return new PersonalField(
                    false,
                    null,
                    null,
                    "NOT_FOUND",
                    List.of(),
                    List.of(),
                    List.of(new Issue("FIELD_DISABLED", field, List.of())));
        var alternatives = new LinkedHashMap<String, Alternative>();
        var valid = new LinkedHashMap<String, List<Candidate>>();
        var issues = new ArrayList<Issue>();
        boolean uncertain = false;
        String selectedRaw = null;
        for (var value : values) {
            var checked = check(field, value.raw(), value.logical());
            var previous = alternatives.get(value.raw());
            var spans = new ArrayList<>(value.evidence());
            if (previous != null) spans.addAll(previous.evidence());
            alternatives.put(
                    value.raw(),
                    new Alternative(
                            value.raw(), checked.normalized(), spans.stream().distinct().toList()));
            if (checked.issue() != null)
                issues.add(new Issue(checked.issue(), field, value.evidence()));
            if (checked.normalized() != null) {
                valid.computeIfAbsent(checked.normalized(), ignored -> new ArrayList<>())
                        .add(value);
                if (selectedRaw == null) selectedRaw = checked.raw();
            }
            if (value.issue() != null)
                issues.add(new Issue(value.issue(), field, value.evidence()));
            uncertain |= value.uncertain();
        }
        if (uncertain) issues.add(new Issue("READING_ORDER_UNVERIFIED", field, List.of()));
        if (valid.size() > 1) issues.add(new Issue("CONFLICTING_VALUES", field, List.of()));
        String state =
                values.isEmpty()
                        ? "NOT_FOUND"
                        : uncertain || valid.size() > 1
                                ? "AMBIGUOUS"
                                : valid.isEmpty() ? "INVALID" : "PROPOSED";
        var evidence =
                state.equals("PROPOSED")
                        ? valid.values().iterator().next().stream()
                                .flatMap(value -> value.evidence().stream())
                                .distinct()
                                .toList()
                        : List.<Evidence>of();
        if (unlabeledFields.contains(field))
            issues.add(new Issue("UNLABELED_SINGLE_VALUE", field, evidence));
        return new PersonalField(
                true,
                state.equals("PROPOSED") ? selectedRaw : null,
                state.equals("PROPOSED") ? valid.keySet().iterator().next() : null,
                state,
                List.copyOf(alternatives.values()),
                evidence,
                issues.stream().distinct().toList());
    }
}
