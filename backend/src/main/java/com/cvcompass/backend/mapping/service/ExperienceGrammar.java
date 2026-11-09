package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Company;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Experience;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Issue;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Period;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PeriodProposal;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Title;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.service.ExperienceGrammarRows.Atom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Typed block relationships for the personal-info grammar engines. */
final class ExperienceGrammar {
    record Outcome(List<Experience> experiences, List<Issue> issues) {}

    private final Extraction input;
    private final ExperienceDefinition config;
    private final List<SourceLine> lines;
    private final ExperienceGrammarRows rows;
    private final List<Block> blocks = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();

    private final boolean v34;

    ExperienceGrammar(Extraction input, ExperienceDefinition config) {
        this(input, config, false);
    }

    ExperienceGrammar(Extraction input, ExperienceDefinition config, boolean v34) {
        this.v34 = v34;
        this.input = input;
        this.config = config;
        this.lines = SourceLine.read(input.id(), input.pages());
        this.rows = new ExperienceGrammarRows(input, config, lines);
    }

    Outcome parse() {
        Block current = null;
        var pendingPeriods = new ArrayList<PeriodProposal>();
        var pendingTitles = new ArrayList<Atom>();
        String section = "UNSCOPED";
        int epoch = 0;
        int pendingPage = -1;
        SourceLine rejectedRange = null;
        for (int index = 0; index < lines.size(); index++) {
            if (pendingPeriods.size() + pendingTitles.size() > 8)
                throw new IllegalArgumentException("Unresolved experience anchor limit exceeded");
            var line = lines.get(index);
            if (line.text.matches(
                    "(?iu)(?:page\\s+\\d+(?:\\s+of\\s+\\d+)?|\\d{1,3}|private and confidential|confidential)"))
                continue;
            String heading = section(line.text);
            if (heading != null) {
                if (!heading.equals(section)) epoch++;
                section = heading;
                current = null;
                rejectedRange = null;
                pendingPeriods.clear();
                pendingTitles.clear();
                continue;
            }
            if (section.equals("STOP")) continue;
            boolean anchored = v34 && (section.equals("DETAIL") || section.equals("SUMMARY"));
            boolean contextual = v34 && section.equals("CONTEXT");
            var range = anchored ? ExperienceGrammarDates.find(line) : null;
            var row =
                    range != null && range.proposal().state().equals("INVALID")
                            ? null
                            : contextual ? rows.contextRow(index) : rows.parse(index, anchored);
            if (range != null && (row == null || row.ambiguous() || row.contextOnly())) {
                issues.add(new Issue("AMBIGUOUS_EXPERIENCE_ROW", "source", List.of(line.whole())));
                current = null;
                pendingPeriods.clear();
                pendingTitles.clear();
                rejectedRange = line;
                continue;
            }
            if (rejectedRange != null
                    && rejectedRange.page == line.page
                    && row.company() == null
                    && row.periods().isEmpty()
                    && !row.titles().isEmpty()) {
                var evidence = new ArrayList<Evidence>();
                evidence.add(rejectedRange.whole());
                row.titles().forEach(title -> evidence.addAll(title.evidence()));
                issues.add(new Issue("UNRESOLVED_EXPERIENCE_BLOCK", "source", evidence));
                index = row.consumedThrough();
                continue;
            }
            rejectedRange = null;
            if (row != null) index = row.consumedThrough();
            if (pendingPage != -1 && pendingPage != line.page) {
                if (!pendingPeriods.isEmpty() || !pendingTitles.isEmpty())
                    issues.add(new Issue("UNLINKED_PAGE_TEXT", "source", List.of(line.whole())));
                pendingPeriods.clear();
                pendingTitles.clear();
                pendingPage = -1;
            }
            if (section.equals("CONTEXT") && (!v34 || row == null)) {
                issues.add(
                        new Issue(
                                "UNCLASSIFIED_EXPERIENCE_CONTEXT",
                                "source",
                                List.of(line.whole())));
                continue;
            }
            if (row.ambiguous()) {
                issues.add(new Issue("AMBIGUOUS_EXPERIENCE_ROW", "source", List.of(line.whole())));
                pendingPeriods.clear();
                pendingTitles.clear();
                current = null;
                continue;
            }
            if (row.continued()) {
                Block previous = blocks.isEmpty() ? null : blocks.getLast();
                boolean verified =
                        row.company() != null
                                && previous != null
                                && previous.epoch == epoch
                                && previous.lastPage + 1 == line.page
                                && companyKey(previous.company).equals(companyKey(row.company()))
                                && input.pages().stream()
                                        .anyMatch(
                                                page ->
                                                        page.number() == line.page - 1
                                                                && page.status().equals("ok"));
                if (!verified) {
                    issues.add(new Issue("ORPHAN_CONTINUATION", "source", List.of(line.whole())));
                    current = null;
                    pendingPeriods.clear();
                    pendingTitles.clear();
                    continue;
                }
                current = previous;
                current.lastPage = line.page;
                current.evidence.add(line.whole());
                current.titles.addAll(row.titles());
                current.periods.addAll(row.periods());
                continue;
            }
            if (current != null && current.lastPage != line.page) {
                if (row.company() == null)
                    issues.add(new Issue("UNLINKED_PAGE_TEXT", "source", List.of(line.whole())));
                current = null;
                pendingPeriods.clear();
                pendingTitles.clear();
            }
            if (row.contextOnly()) {
                if (current != null) describe(current, line);
                continue;
            }
            if (row.company() != null) {
                if (section.equals("UNSCOPED")
                        && current == null
                        && !rows.explicitEmployment(line)) {
                    issues.add(
                            new Issue(
                                    "UNSCOPED_EMPLOYER_CONTEXT", "source", List.of(line.whole())));
                    pendingPeriods.clear();
                    pendingTitles.clear();
                    continue;
                }
                current = block(row.company(), section.equals("SUMMARY"), line.page, epoch);
                current.headerEnd = index;
                if (contextual)
                    current.issues.add(
                            new Issue(
                                    "ROLE_CONTEXT_UNCLASSIFIED",
                                    "experience",
                                    List.of(line.whole())));
                current.titles.addAll(pendingTitles);
                current.titles.addAll(row.titles());
                current.periods.addAll(pendingPeriods);
                current.periods.addAll(row.periods());
                current.periodBeforeTitle =
                        !pendingPeriods.isEmpty()
                                || !row.periods().isEmpty() && row.titles().isEmpty();
                pendingTitles.clear();
                pendingPeriods.clear();
                continue;
            }
            if (!row.titles().isEmpty()) {
                if (section.equals("UNSCOPED") && current == null) continue;
                boolean unlabelledTitle =
                        anchored && row.periods().isEmpty() && rows.adjacentTitle(index);
                boolean beforeCompany =
                        unlabelledTitle && opensCompanyAfterTitle(index, line.page, current);
                if (beforeCompany) current = null;
                if (current == null) {
                    if (unlabelledTitle && !beforeCompany) {
                        issues.add(
                                new Issue(
                                        "UNRESOLVED_EXPERIENCE_BLOCK",
                                        "source",
                                        List.of(line.whole())));
                        continue;
                    }
                    pendingTitles.addAll(row.titles());
                    pendingPeriods.addAll(row.periods());
                    pendingPage = line.page;
                } else {
                    var currentKeys = currentTitleKeys(current);
                    boolean different =
                            !current.titles.isEmpty()
                                    && row.titles().stream()
                                            .noneMatch(
                                                    title -> currentKeys.contains(titleKey(title)));
                    if (different) {
                        if (current.periodBeforeTitle && (!anchored || row.periods().isEmpty())) {
                            current.issues.add(
                                    new Issue(
                                            "EMPLOYER_PERIOD_NOT_ASSIGNED",
                                            "period",
                                            current.periods.stream()
                                                    .flatMap(value -> value.evidence().stream())
                                                    .toList()));
                            current.periods.clear();
                        }
                        current = block(current.company, current.summary, line.page, epoch);
                        current.headerEnd = index;
                    }
                    current.titles.addAll(row.titles());
                    current.periods.addAll(row.periods());
                }
                continue;
            }
            if (!row.periods().isEmpty()) {
                boolean nextCompany =
                        index + 1 < lines.size()
                                && lines.get(index + 1).page == line.page
                                && rows.parse(index + 1, anchored).company() != null;
                if (current == null || nextCompany && !current.periods.isEmpty()) {
                    pendingPeriods.addAll(row.periods());
                    pendingPage = line.page;
                    if (nextCompany) current = null;
                } else {
                    current.periodBeforeTitle = current.titles.isEmpty();
                    if (current.headerEnd == index - 1) current.headerEnd = index;
                    current.periods.addAll(row.periods());
                }
                continue;
            }
            if (current != null) describe(current, line);
            else if (!pendingPeriods.isEmpty() || !pendingTitles.isEmpty()) {
                issues.add(
                        new Issue("UNRESOLVED_EXPERIENCE_BLOCK", "source", List.of(line.whole())));
                pendingTitles.clear();
                pendingPeriods.clear();
            }
        }
        if (!pendingPeriods.isEmpty() || !pendingTitles.isEmpty()) {
            var evidence = new ArrayList<Evidence>();
            pendingPeriods.forEach(value -> evidence.addAll(value.evidence()));
            pendingTitles.forEach(value -> evidence.addAll(value.evidence()));
            issues.add(new Issue("UNRESOLVED_EXPERIENCE_BLOCK", "source", evidence));
        }
        mergeOverview();
        markOverlaps();
        var result = new ArrayList<Experience>();
        for (var block : blocks)
            if (!block.consumed) result.add(render("exp_%03d".formatted(result.size() + 1), block));
        if (result.isEmpty())
            issues.add(new Issue("EXPERIENCE_NOT_FOUND", "experiences", List.of()));
        issues.add(new Issue("READING_ORDER_UNVERIFIED", "source", List.of()));
        if (!input.status().equals("success")
                || input.pages().stream()
                        .anyMatch(
                                page -> List.of("failed", "not_processed").contains(page.status())))
            issues.add(new Issue("SOURCE_INCOMPLETE", "source", List.of()));
        return new Outcome(List.copyOf(result), List.copyOf(issues));
    }

    private boolean opensCompanyAfterTitle(int index, int page, Block preceding) {
        boolean precedingNeedsTitle = preceding != null && preceding.titles.isEmpty();
        if (index + 1 >= lines.size() || precedingNeedsTitle && preceding.headerEnd == index - 1)
            return false;
        var next = lines.get(index + 1);
        if (next.page != page || section(next.text) != null) return false;
        var nextRow = rows.parse(index + 1, true);
        // Between dated headings, fill the preceding employer's title first.
        boolean anchoredToPreceding =
                precedingNeedsTitle && !preceding.periods.isEmpty() && !nextRow.periods().isEmpty();
        return !anchoredToPreceding
                && nextRow.company() != null
                && nextRow.titles().isEmpty()
                && !nextRow.continued()
                && !nextRow.ambiguous();
    }

    private Block block(Atom company, boolean summary, int page, int epoch) {
        if (blocks.size() >= 100)
            throw new IllegalArgumentException("Experience count limit exceeded");
        var block = new Block(company, summary, page, epoch);
        blocks.add(block);
        return block;
    }

    private void describe(Block block, SourceLine line) {
        if (line.text.matches(
                "(?iu)(?:page\\s+\\d+(?:\\s+of\\s+\\d+)?|\\d{1,3}|private and confidential|confidential)"))
            return;
        block.description.add(line.rawWhole());
        block.evidence.add(line.rawWhole());
        block.descriptionSize += line.raw.codePointCount(0, line.raw.length());
        if (block.descriptionSize > 16384)
            throw new IllegalArgumentException("Experience description limit exceeded");
    }

    private String section(String text) {
        String key = key(text.replaceFirst("[:：]$", ""));
        if (contains(config.rules().experienceHeadings(), key)) return "DETAIL";
        if (contains(config.rules().summaryHeadings(), key)) return "SUMMARY";
        if (ExperienceGrammarRows.contextHeading(key, v34)) return "CONTEXT";
        if (rows.stopHeading(text, v34)) return "STOP";
        return null;
    }

    private void mergeOverview() {
        for (var summary : blocks) {
            if (!summary.summary) continue;
            var matches =
                    blocks.stream()
                            .filter(
                                    detail ->
                                            !detail.summary
                                                    && companyKey(summary.company)
                                                            .equals(companyKey(detail.company))
                                                    && (compatibleTitle(summary, detail)
                                                                    && compatibleStart(
                                                                            summary, detail)
                                                            || v34
                                                                    && summary.titles.isEmpty()
                                                                    && sameResolvedPeriod(
                                                                            summary, detail)))
                            .toList();
            if (matches.size() == 1) {
                var detail = matches.getFirst();
                detail.evidence.addAll(summary.evidence);
                detail.evidence.addAll(summary.company.evidence());
                detail.periods.addAll(summary.periods);
                detail.titles.addAll(summary.titles);
                detail.issues.add(
                        new Issue("SUMMARY_DETAIL_MERGED", "experience", allEvidence(summary)));
                summary.consumed = true;
            } else if (matches.size() > 1) {
                issues.add(
                        new Issue("AMBIGUOUS_SUMMARY_LINK", "experiences", allEvidence(summary)));
            } else if (!v34
                    && summary.titles.isEmpty()
                    && blocks.stream()
                            .anyMatch(
                                    detail ->
                                            !detail.summary
                                                    && companyKey(summary.company)
                                                            .equals(companyKey(detail.company))
                                                    && compatibleStart(summary, detail))) {
                summary.consumed = true;
                issues.add(
                        new Issue("AMBIGUOUS_SUMMARY_LINK", "experiences", allEvidence(summary)));
            }
        }
    }

    private boolean compatibleTitle(Block left, Block right) {
        return !left.titles.isEmpty()
                && !right.titles.isEmpty()
                && left.titles.stream()
                        .anyMatch(title -> currentTitleKeys(right).contains(titleKey(title)));
    }

    private static boolean sameResolvedPeriod(Block left, Block right) {
        var first = period(left);
        var second = period(right);
        return first.state().equals("PROPOSED")
                && second.state().equals("PROPOSED")
                && first.start() != null
                && first.start().equals(second.start())
                && (first.end() != null || first.ongoingAsStated())
                && Objects.equals(first.end(), second.end())
                && first.ongoingAsStated() == second.ongoingAsStated();
    }

    private static boolean compatibleStart(Block left, Block right) {
        if (left.periods.isEmpty()) return true;
        return left.periods.stream()
                .anyMatch(
                        a ->
                                a.start() != null
                                        && right.periods.stream()
                                                .anyMatch(
                                                        b ->
                                                                b.start() != null
                                                                        && !a.start()
                                                                                .earliest()
                                                                                .isAfter(
                                                                                        b.start()
                                                                                                .latest())
                                                                        && !b.start()
                                                                                .earliest()
                                                                                .isAfter(
                                                                                        a.start()
                                                                                                .latest())));
    }

    private void markOverlaps() {
        for (int i = 0; i < blocks.size(); i++)
            for (int j = i + 1; j < blocks.size(); j++) {
                var left = blocks.get(i);
                var right = blocks.get(j);
                if (left.consumed || right.consumed) continue;
                var a = period(left);
                var b = period(right);
                if (!a.state().equals("PROPOSED")
                        || !b.state().equals("PROPOSED")
                        || a.start() == null
                        || b.start() == null
                        || a.end() == null
                        || b.end() == null) continue;
                if (!a.start().earliest().isAfter(b.end().latest())
                        && !b.start().earliest().isAfter(a.end().latest())) {
                    var evidence = new ArrayList<>(a.evidence());
                    evidence.addAll(b.evidence());
                    left.issues.add(new Issue("POSSIBLE_PERIOD_OVERLAP", "period", evidence));
                    right.issues.add(new Issue("POSSIBLE_PERIOD_OVERLAP", "period", evidence));
                }
            }
    }

    private Experience render(String id, Block block) {
        var companyRule =
                config.companies().stream()
                        .filter(rule -> contains(rule.aliases(), key(block.company.text())))
                        .findFirst()
                        .orElse(null);
        var company =
                new Company(
                        block.company.raw(),
                        companyKey(block.company),
                        "PROPOSED",
                        companyRule == null ? List.of() : companyRule.industryCodes(),
                        companyRule == null || companyRule.industryCodes().isEmpty()
                                ? "NOT_FOUND"
                                : "PROPOSED",
                        companyRule == null ? null : companyRule.basis(),
                        block.company.evidence());
        var keys = currentTitleKeys(block);
        var rawTitles = block.titles.stream().map(Atom::raw).distinct().toList();
        var titleRule =
                keys.size() != 1
                        ? null
                        : config.titles().stream()
                                .filter(
                                        rule ->
                                                block.titles.stream()
                                                        .anyMatch(
                                                                title ->
                                                                        contains(
                                                                                rule.aliases(),
                                                                                key(title.text()))))
                                .findFirst()
                                .orElse(null);
        var titleEvidence =
                block.titles.stream()
                        .flatMap(value -> value.evidence().stream())
                        .distinct()
                        .toList();
        var title =
                new Title(
                        keys.size() == 1 ? rawTitles.getFirst() : null,
                        titleRule == null ? null : titleRule.canonical(),
                        rawTitles.isEmpty()
                                ? "NOT_FOUND"
                                : keys.size() > 1 ? "AMBIGUOUS" : "PROPOSED",
                        titleRule == null ? List.of() : titleRule.functionCodes(),
                        titleRule == null || titleRule.functionCodes().isEmpty()
                                ? "NOT_FOUND"
                                : "PROPOSED",
                        titleRule == null ? null : titleRule.seniorityCode(),
                        titleEvidence,
                        rawTitles);
        var period = period(block);
        var notes = new ArrayList<>(block.issues);
        if (title.state().equals("NOT_FOUND"))
            notes.add(new Issue("TITLE_NOT_FOUND", "title", List.of()));
        if (title.state().equals("AMBIGUOUS"))
            notes.add(new Issue("TITLE_CONFLICT", "title", titleEvidence));
        if (!period.state().equals("PROPOSED"))
            notes.add(
                    new Issue(
                            period.state().equals("NOT_FOUND")
                                    ? "PERIOD_NOT_FOUND"
                                    : period.state().equals("INVALID")
                                            ? "INVALID_PERIOD"
                                            : "PERIOD_CONFLICT",
                            "period",
                            period.evidence()));
        if (period.ongoingAsStated())
            notes.add(new Issue("ONGOING_AS_STATED_UNVERIFIED", "period", period.evidence()));
        if (company.industryState().equals("NOT_FOUND"))
            notes.add(new Issue("INDUSTRY_UNKNOWN", "company.industry", company.evidence()));
        if (title.functionState().equals("NOT_FOUND"))
            notes.add(new Issue("FUNCTION_UNKNOWN", "title.function", title.evidence()));
        if (block.titles.stream()
                .anyMatch(
                        value ->
                                value.text()
                                        .matches(
                                                "(?iu).*\\b(?:co-founder|shareholder|advisory|consulting|part-time|concurrent)\\b.*")))
            notes.add(new Issue("ROLE_CONTEXT_UNCLASSIFIED", "title", titleEvidence));
        return new Experience(
                id,
                company,
                title,
                period,
                String.join("\n", block.description.stream().map(Evidence::text).toList()),
                block.description,
                allEvidence(block),
                notes);
    }

    private static List<Evidence> allEvidence(Block block) {
        var spans = new ArrayList<>(block.evidence);
        spans.addAll(block.company.evidence());
        block.titles.forEach(value -> spans.addAll(value.evidence()));
        block.periods.forEach(value -> spans.addAll(value.evidence()));
        return spans.stream().distinct().toList();
    }

    private static Period period(Block block) {
        if (block.periods.isEmpty())
            return new Period(null, null, false, "NOT_FOUND", List.of(), List.of());
        var spans =
                block.periods.stream()
                        .flatMap(value -> value.evidence().stream())
                        .distinct()
                        .toList();
        long count =
                block.periods.stream()
                        .map(
                                value ->
                                        List.of(
                                                Objects.toString(value.start()),
                                                Objects.toString(value.end()),
                                                Boolean.toString(value.ongoingAsStated()),
                                                value.state()))
                        .distinct()
                        .count();
        if (count > 1)
            return new Period(
                    null,
                    null,
                    block.periods.stream().anyMatch(PeriodProposal::ongoingAsStated),
                    "AMBIGUOUS",
                    block.periods,
                    spans);
        var value = block.periods.getFirst();
        return new Period(
                value.start(),
                value.end(),
                value.ongoingAsStated(),
                value.state(),
                block.periods,
                spans);
    }

    private String companyKey(Atom company) {
        return config.companies().stream()
                .filter(rule -> contains(rule.aliases(), key(company.text())))
                .map(rule -> key(rule.canonical()))
                .findFirst()
                .orElse(key(company.text()));
    }

    private String titleKey(Atom title) {
        return config.titles().stream()
                .filter(rule -> contains(rule.aliases(), key(title.text())))
                .map(rule -> key(rule.canonical()))
                .findFirst()
                .orElse(key(title.text()));
    }

    private List<String> currentTitleKeys(Block block) {
        return block.titles.stream().map(this::titleKey).distinct().toList();
    }

    private static String key(String text) {
        return ExperienceDefinition.key(text);
    }

    private static boolean contains(List<String> values, String key) {
        return values.stream().anyMatch(value -> key(value).equals(key));
    }

    private static final class Block {
        final Atom company;
        final boolean summary;
        final int epoch;
        final List<Atom> titles = new ArrayList<>();
        final List<PeriodProposal> periods = new ArrayList<>();
        final List<Evidence> description = new ArrayList<>();
        final List<Evidence> evidence = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();
        int lastPage, descriptionSize;
        int headerEnd = -2;
        boolean consumed, periodBeforeTitle;

        Block(Atom company, boolean summary, int page, int epoch) {
            this.company = company;
            this.summary = summary;
            this.lastPage = page;
            this.epoch = epoch;
        }
    }
}
