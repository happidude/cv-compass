package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Company;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Experience;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Issue;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Period;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PeriodProposal;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Title;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ExperienceEngine {
    public static final String VERSION = ExperienceDefinition.ENGINE_VERSION;
    private static final Pattern STATEMENT =
            Pattern.compile(
                    "^(?:have been working at|worked at|working at|làm việc tại)\\s+(.+?)\\s+(?:from|từ)\\s+(.+?)[.]?$",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private final MappingEngine evidenceValidator;

    public ExperienceEngine(MappingEngine evidenceValidator) {
        this.evidenceValidator = evidenceValidator;
    }

    public ExperienceMappingResult map(MappingClaim claim, Extraction input) {
        if (claim.extractionId() != input.id()
                || !VERSION.equals(claim.engineVersion())
                || !(claim.definition() instanceof ExperienceDefinition definition)) {
            throw new IllegalArgumentException("Experience engine/config/input mismatch");
        }
        definition.validate();
        boolean incomplete =
                !"success".equals(input.status())
                        || input.pages().stream()
                                .anyMatch(
                                        page ->
                                                List.of("failed", "not_processed")
                                                        .contains(page.status()));
        String rejection =
                input.legacy()
                        ? "LEGACY_UNPAGED"
                        : !List.of("success", "partial").contains(input.status())
                                ? "OCR_UNUSABLE"
                                : input.pages().stream()
                                                .noneMatch(page -> "ok".equals(page.status()))
                                        ? "NO_OK_PAGES"
                                        : null;
        var issues = new ArrayList<Issue>();
        var blocks = new ArrayList<Block>();
        if (rejection == null) parse(input, definition, blocks, issues);
        mergeSummaries(blocks, issues, definition);
        markOverlaps(blocks);
        var experiences = new ArrayList<Experience>();
        for (Block block : blocks) {
            if (block.consumed) continue;
            experiences.add(
                    render("exp_%03d".formatted(experiences.size() + 1), block, definition));
        }
        if (rejection == null) {
            issues.add(new Issue("READING_ORDER_UNVERIFIED", "source", List.of()));
            if (incomplete) issues.add(new Issue("SOURCE_INCOMPLETE", "source", List.of()));
            if (experiences.isEmpty())
                issues.add(new Issue("EXPERIENCE_NOT_FOUND", "experiences", List.of()));
        }
        for (var experience : experiences) {
            for (var span : experience.evidence()) evidenceValidator.validateEvidence(input, span);
        }
        return new ExperienceMappingResult(
                2,
                input.id(),
                input.schemaVersion(),
                claim.configId(),
                claim.configRevision(),
                VERSION,
                rejection == null ? "NEEDS_REVIEW" : "UNSUPPORTED_INPUT",
                incomplete,
                rejection,
                List.of(),
                MappingDigests.sha256(MappingDigests.canonical(input)),
                input.documentId(),
                input.profileRevision(),
                definition.rulesRevision(),
                definition.taxonomyRevision(),
                experiences,
                issues);
    }

    private void parse(
            Extraction input,
            ExperienceDefinition definition,
            List<Block> blocks,
            List<Issue> issues) {
        String section = "UNSCOPED";
        Block current = null;
        for (SourceLine line : SourceLine.read(input.id(), input.pages())) {
            String heading = key(line.text.replaceFirst("[:：]$", ""));
            var rules = definition.rules();
            if (contains(rules.experienceHeadings(), heading)
                    || contains(rules.summaryHeadings(), heading)) {
                section = contains(rules.summaryHeadings(), heading) ? "SUMMARY" : "DETAIL";
                current = null;
                continue;
            }
            if (contains(rules.stopHeadings(), heading)) {
                section = "STOP";
                current = null;
                continue;
            }
            if ("STOP".equals(section)) continue;
            Value employer = label(line, rules.employerLabels());
            Value inlinePeriod = null;
            var statement = STATEMENT.matcher(line.text);
            if (employer == null && statement.matches()) {
                employer = value(line, statement.start(1), statement.end(1));
                inlinePeriod = value(line, statement.start(2), statement.end(2));
            }
            if (employer != null) {
                boolean continued = false;
                for (String marker : rules.continuationMarkers()) {
                    int markerStart = employer.text.lastIndexOf(" (");
                    if (markerStart > 0
                            && employer.text.endsWith(")")
                            && key(employer.text.substring(
                                            markerStart + 2, employer.text.length() - 1))
                                    .equals(key(marker))) {
                        int end = employer.start + markerStart;
                        employer = value(line, employer.start, end);
                        continued = true;
                        break;
                    }
                }
                Block previous = blocks.isEmpty() ? null : blocks.getLast();
                boolean link =
                        continued
                                && previous != null
                                && previous.lastPage + 1 == line.page
                                && previous.summary == "SUMMARY".equals(section)
                                && companyKey(previous, definition)
                                        .equals(companyKey(employer, definition));
                if (continued && !link) {
                    issues.add(
                            new Issue("ORPHAN_CONTINUATION", "source", List.of(employer.evidence)));
                    current = null;
                    continue;
                }
                if (link) {
                    current = previous;
                    current.evidence.add(employer.evidence);
                    current.lastPage = line.page;
                } else {
                    current = new Block(employer, "SUMMARY".equals(section), line.page);
                    blocks.add(current);
                    if (continued)
                        current.issues.add(
                                new Issue(
                                        "ORPHAN_CONTINUATION",
                                        "experience",
                                        List.of(employer.evidence)));
                }
                if (inlinePeriod != null) addPeriod(current, line, inlinePeriod);
                if (blocks.size() > 100)
                    throw new IllegalArgumentException("Experience count limit exceeded");
                continue;
            }
            // Three explicit columns: period | employer | title, inside an experience section.
            int pipe = line.text.indexOf('|'),
                    second = pipe < 0 ? -1 : line.text.indexOf('|', pipe + 1);
            if (!"UNSCOPED".equals(section) && pipe > 0 && second > pipe) {
                Value candidate = value(line, 0, pipe);
                if (ExperienceDates.range(candidate.text).matches()) {
                    current =
                            new Block(
                                    value(line, pipe + 1, second),
                                    "SUMMARY".equals(section),
                                    line.page);
                    blocks.add(current);
                    current.titles.add(value(line, second + 1, line.text.length()));
                    current.evidence.add(current.titles.getFirst().evidence);
                    addPeriod(current, line, candidate);
                    if (blocks.size() > 100)
                        throw new IllegalArgumentException("Experience count limit exceeded");
                    continue;
                }
            }
            if (current != null && current.lastPage != line.page) {
                // Adjacency alone is not proof of continuation; a failed intervening page never
                // bridges.
                issues.add(new Issue("UNLINKED_PAGE_TEXT", "source", List.of(line.whole())));
                current = null;
            }
            if (current == null) continue;
            Value title = label(line, rules.titleLabels());
            if (title != null) {
                if (!current.titles.isEmpty()
                        && !current.periods.isEmpty()
                        && current.titles.stream()
                                .noneMatch(
                                        old ->
                                                titleKey(old.text, definition)
                                                        .equals(
                                                                titleKey(
                                                                        title.text, definition)))) {
                    current = new Block(current.employer, current.summary, line.page);
                    blocks.add(current);
                    if (blocks.size() > 100)
                        throw new IllegalArgumentException("Experience count limit exceeded");
                }
                current.titles.add(title);
                current.evidence.add(title.evidence);
                continue;
            }
            Value period = label(line, rules.periodLabels());
            if (period == null) {
                var range = ExperienceDates.range(line.text);
                if (range.find()
                        && (range.start() == 0
                                || line.text
                                        .substring(0, range.start())
                                        .matches("(?iu)(from|từ)\\s+"))
                        && range.end() >= line.text.length() - 1) {
                    period = value(line, range.start(), range.end());
                }
            }
            if (period != null) {
                addPeriod(current, line, period);
                continue;
            }
            current.description.add(line.rawWhole());
            current.evidence.add(line.rawWhole());
            current.descriptionSize += line.raw.codePointCount(0, line.raw.length());
            if (current.descriptionSize > 16384)
                throw new IllegalArgumentException("Experience description limit exceeded");
        }
    }

    private static Value label(SourceLine line, List<String> labels) {
        int colon = line.text.indexOf(':');
        if (colon < 0) colon = line.text.indexOf('：');
        if (colon < 1 || !contains(labels, key(line.text.substring(0, colon)))) return null;
        if (line.text.substring(colon + 1).isBlank()) return null;
        return value(line, colon + 1, line.text.length());
    }

    private static Value value(SourceLine line, int start, int end) {
        while (start < end && Character.isWhitespace(line.text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(line.text.charAt(end - 1))) end--;
        if (start == end || line.text.codePointCount(start, end) > 4096)
            throw new IllegalArgumentException("Experience field limit exceeded");
        return new Value(line.text.substring(start, end), line.span(start, end), start, end);
    }

    private static void addPeriod(Block block, SourceLine line, Value period) {
        PeriodProposal proposal = ExperienceDates.parse(line, period.start, period.end);
        block.periods.add(proposal);
        block.evidence.addAll(proposal.evidence());
    }

    private static void mergeSummaries(
            List<Block> blocks, List<Issue> issues, ExperienceDefinition config) {
        for (Block summary : blocks) {
            if (!summary.summary) continue;
            var matches =
                    blocks.stream()
                            .filter(
                                    detail ->
                                            !detail.summary
                                                    && companyKey(detail, config)
                                                            .equals(companyKey(summary, config))
                                                    && compatibleTitles(summary, detail, config)
                                                    && (sameStart(summary, detail)
                                                            || summary.periods.isEmpty()
                                                                    && !summary.titles.isEmpty()
                                                                    && !detail.titles.isEmpty()))
                            .toList();
            if (matches.size() == 1) {
                Block detail = matches.getFirst();
                detail.evidence.addAll(summary.evidence);
                detail.periods.addAll(summary.periods);
                if (detail.titles.isEmpty()) detail.titles.addAll(summary.titles);
                detail.issues.add(
                        new Issue("SUMMARY_DETAIL_MERGED", "experience", summary.evidence));
                summary.consumed = true;
            } else if (matches.size() > 1) {
                summary.consumed = true;
                issues.add(new Issue("AMBIGUOUS_SUMMARY_LINK", "experiences", summary.evidence));
            }
        }
    }

    private static String companyKey(Block block, ExperienceDefinition config) {
        return companyKey(block.employer, config);
    }

    private static String companyKey(Value employer, ExperienceDefinition config) {
        return config.companies().stream()
                .filter(rule -> contains(rule.aliases(), key(employer.text)))
                .map(rule -> key(rule.canonical()))
                .findFirst()
                .orElse(key(employer.text));
    }

    private static boolean compatibleTitles(Block a, Block b, ExperienceDefinition config) {
        return a.titles.isEmpty()
                || b.titles.isEmpty()
                || a.titles.stream()
                        .anyMatch(
                                x ->
                                        b.titles.stream()
                                                .anyMatch(
                                                        y ->
                                                                titleKey(x.text, config)
                                                                        .equals(
                                                                                titleKey(
                                                                                        y.text,
                                                                                        config))));
    }

    private static String titleKey(String text, ExperienceDefinition config) {
        return config.titles().stream()
                .filter(rule -> contains(rule.aliases(), key(text)))
                .map(rule -> key(rule.canonical()))
                .findFirst()
                .orElse(key(text));
    }

    private static boolean sameStart(Block a, Block b) {
        return a.periods.stream()
                .anyMatch(
                        x ->
                                x.start() != null
                                        && b.periods.stream()
                                                .anyMatch(
                                                        y ->
                                                                y.start() != null
                                                                        && !x.start()
                                                                                .earliest()
                                                                                .isAfter(
                                                                                        y.start()
                                                                                                .latest())
                                                                        && !y.start()
                                                                                .earliest()
                                                                                .isAfter(
                                                                                        x.start()
                                                                                                .latest())));
    }

    private static void markOverlaps(List<Block> blocks) {
        for (int i = 0; i < blocks.size(); i++)
            for (int j = i + 1; j < blocks.size(); j++) {
                Block a = blocks.get(i), b = blocks.get(j);
                if (a.consumed || b.consumed) continue;
                Period x = period(a), y = period(b);
                if (!"PROPOSED".equals(x.state())
                        || !"PROPOSED".equals(y.state())
                        || x.start() == null
                        || y.start() == null
                        || x.end() == null
                        || y.end() == null) continue;
                if (!x.start().earliest().isAfter(y.end().latest())
                        && !y.start().earliest().isAfter(x.end().latest())) {
                    var spans = new ArrayList<Evidence>(x.evidence());
                    spans.addAll(y.evidence());
                    a.issues.add(new Issue("POSSIBLE_PERIOD_OVERLAP", "period", spans));
                    b.issues.add(new Issue("POSSIBLE_PERIOD_OVERLAP", "period", spans));
                }
            }
    }

    private static Experience render(String id, Block block, ExperienceDefinition config) {
        var companyRules =
                config.companies().stream()
                        .filter(rule -> contains(rule.aliases(), key(block.employer.text)))
                        .toList();
        var companyRule = companyRules.isEmpty() ? null : companyRules.getFirst();
        var company =
                new Company(
                        block.employer.evidence.text(),
                        companyRule == null
                                ? key(block.employer.text)
                                : key(companyRule.canonical()),
                        "PROPOSED",
                        companyRule == null ? List.of() : companyRule.industryCodes(),
                        companyRule == null || companyRule.industryCodes().isEmpty()
                                ? "NOT_FOUND"
                                : "PROPOSED",
                        companyRule == null ? null : companyRule.basis(),
                        List.of(block.employer.evidence));
        var titleTexts = block.titles.stream().map(v -> v.evidence.text()).distinct().toList();
        var titleKeys =
                block.titles.stream().map(v -> titleKey(v.text, config)).distinct().toList();
        boolean ambiguous = titleKeys.size() > 1;
        var titleRule =
                titleKeys.size() != 1
                        ? null
                        : config.titles().stream()
                                .filter(
                                        rule ->
                                                block.titles.stream()
                                                        .anyMatch(
                                                                v ->
                                                                        contains(
                                                                                rule.aliases(),
                                                                                key(v.text))))
                                .findFirst()
                                .orElse(null);
        var title =
                new Title(
                        titleKeys.size() == 1 ? titleTexts.getFirst() : null,
                        titleRule == null ? null : titleRule.canonical(),
                        titleTexts.isEmpty() ? "NOT_FOUND" : ambiguous ? "AMBIGUOUS" : "PROPOSED",
                        titleRule == null ? List.of() : titleRule.functionCodes(),
                        titleRule == null || titleRule.functionCodes().isEmpty()
                                ? "NOT_FOUND"
                                : "PROPOSED",
                        titleRule == null ? null : titleRule.seniorityCode(),
                        block.titles.stream().map(v -> v.evidence).distinct().toList(),
                        titleTexts);
        Period period = period(block);
        var issues = new ArrayList<>(block.issues);
        if (ambiguous) issues.add(new Issue("TITLE_CONFLICT", "title", title.evidence()));
        if ("INVALID".equals(period.state()))
            issues.add(new Issue("INVALID_PERIOD", "period", period.evidence()));
        if ("AMBIGUOUS".equals(period.state()))
            issues.add(new Issue("PERIOD_CONFLICT", "period", period.evidence()));
        if ("NOT_FOUND".equals(period.state()))
            issues.add(new Issue("PERIOD_NOT_FOUND", "period", List.of()));
        if (period.ongoingAsStated())
            issues.add(new Issue("ONGOING_AS_STATED_UNVERIFIED", "period", period.evidence()));
        if (titleTexts.isEmpty()) issues.add(new Issue("TITLE_NOT_FOUND", "title", List.of()));
        if ("NOT_FOUND".equals(company.industryState()))
            issues.add(new Issue("INDUSTRY_UNKNOWN", "company.industry", company.evidence()));
        if ("NOT_FOUND".equals(title.functionState()))
            issues.add(new Issue("FUNCTION_UNKNOWN", "title.function", title.evidence()));
        return new Experience(
                id,
                company,
                title,
                period,
                String.join("\n", block.description.stream().map(Evidence::text).toList()),
                block.description,
                block.evidence.stream().distinct().toList(),
                issues);
    }

    private static Period period(Block block) {
        if (block.periods.isEmpty())
            return new Period(null, null, false, "NOT_FOUND", List.of(), List.of());
        var spans = block.periods.stream().flatMap(p -> p.evidence().stream()).distinct().toList();
        var distinct =
                block.periods.stream()
                        .map(
                                p ->
                                        List.of(
                                                Objects.toString(p.start()),
                                                Objects.toString(p.end()),
                                                Boolean.toString(p.ongoingAsStated()),
                                                p.state()))
                        .distinct()
                        .toList();
        if (distinct.size() > 1)
            return new Period(
                    null,
                    null,
                    block.periods.stream().anyMatch(PeriodProposal::ongoingAsStated),
                    "AMBIGUOUS",
                    block.periods,
                    spans);
        var first = block.periods.getFirst();
        return new Period(
                first.start(),
                first.end(),
                first.ongoingAsStated(),
                first.state(),
                block.periods,
                spans);
    }

    private static boolean contains(List<String> values, String normalized) {
        return values.stream().anyMatch(v -> key(v).equals(normalized));
    }

    private static String key(String value) {
        return ExperienceDefinition.key(value);
    }

    private record Value(String text, Evidence evidence, int start, int end) {}

    private static final class Block {
        final Value employer;
        final boolean summary;
        final List<Value> titles = new ArrayList<>();
        final List<PeriodProposal> periods = new ArrayList<>();
        final List<Evidence> description = new ArrayList<>();
        final List<Evidence> evidence = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();
        int lastPage;
        int descriptionSize;
        boolean consumed;

        Block(Value employer, boolean summary, int page) {
            this.employer = employer;
            this.summary = summary;
            this.lastPage = page;
            evidence.add(employer.evidence);
        }
    }
}
