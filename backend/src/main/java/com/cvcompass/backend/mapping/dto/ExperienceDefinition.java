package com.cvcompass.backend.mapping.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ExperienceDefinition(
        int schemaVersion,
        String engineCompatibility,
        String rulesRevision,
        String taxonomyRevision,
        Rules rules,
        List<TitleRule> titles,
        List<CompanyRule> companies)
        implements MappingConfig {
    public static final String ENGINE_VERSION = "experience-mapping/2.0";

    public ExperienceDefinition {
        titles = titles == null ? List.of() : List.copyOf(titles);
        companies = companies == null ? List.of() : List.copyOf(companies);
    }

    public void validate() {
        if (schemaVersion != 2
                || !ENGINE_VERSION.equals(engineCompatibility)
                || !revision(rulesRevision)
                || !revision(taxonomyRevision)
                || rules == null
                || titles.size() > 256
                || companies.size() > 256) {
            throw new IllegalArgumentException("Unsupported experience configuration");
        }
        for (List<String> values :
                List.of(
                        rules.experienceHeadings(),
                        rules.summaryHeadings(),
                        rules.stopHeadings(),
                        rules.employerLabels(),
                        rules.titleLabels(),
                        rules.periodLabels(),
                        rules.continuationMarkers())) validateNames(values, 32);
        Set<String> titleAliases = new HashSet<>();
        Set<String> titleCanonicals = new HashSet<>();
        for (TitleRule rule : titles) {
            validateNames(rule.aliases(), 32);
            if (!name(rule.canonical())
                    || rule.functionCodes() == null
                    || !codes(rule.functionCodes())
                    || rule.seniorityCode() != null
                            && !rule.seniorityCode().matches("[A-Z][A-Z0-9_]{0,63}")) invalid();
            for (String alias : rule.aliases()) if (!titleAliases.add(key(alias))) invalid();
            if (!titleCanonicals.add(key(rule.canonical()))) invalid();
        }
        Set<String> companyAliases = new HashSet<>();
        Set<String> companyCanonicals = new HashSet<>();
        for (CompanyRule rule : companies) {
            validateNames(rule.aliases(), 32);
            if (!name(rule.canonical())
                    || rule.industryCodes() == null
                    || !codes(rule.industryCodes())
                    || !rule.industryCodes().isEmpty() && !name(rule.basis())) invalid();
            for (String alias : rule.aliases()) if (!companyAliases.add(key(alias))) invalid();
            if (!companyCanonicals.add(key(rule.canonical()))) invalid();
        }
        var headings = new HashSet<String>();
        for (var list :
                List.of(rules.experienceHeadings(), rules.summaryHeadings(), rules.stopHeadings()))
            for (String text : list) if (!headings.add(key(text))) invalid();
        var labels = new HashSet<String>();
        for (var list : List.of(rules.employerLabels(), rules.titleLabels(), rules.periodLabels()))
            for (String text : list) if (!labels.add(key(text))) invalid();
    }

    private static boolean revision(String text) {
        return text != null && text.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private static boolean name(String text) {
        return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 256;
    }

    private static boolean codes(List<String> codes) {
        return codes.size() <= 16
                && codes.stream()
                        .allMatch(code -> code != null && code.matches("[A-Z][A-Z0-9_]{0,63}"));
    }

    private static void validateNames(List<String> list, int cap) {
        if (list == null
                || list.isEmpty()
                || list.size() > cap
                || list.stream().anyMatch(v -> !name(v))) invalid();
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid experience rule or taxonomy");
    }

    public static String key(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .strip()
                .replaceAll("(?U)\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
    }

    public record Rules(
            List<String> experienceHeadings,
            List<String> summaryHeadings,
            List<String> stopHeadings,
            List<String> employerLabels,
            List<String> titleLabels,
            List<String> periodLabels,
            List<String> continuationMarkers) {
        public Rules {
            experienceHeadings = copy(experienceHeadings);
            summaryHeadings = copy(summaryHeadings);
            stopHeadings = copy(stopHeadings);
            employerLabels = copy(employerLabels);
            titleLabels = copy(titleLabels);
            periodLabels = copy(periodLabels);
            continuationMarkers = copy(continuationMarkers);
        }

        private static List<String> copy(List<String> value) {
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    public record TitleRule(
            List<String> aliases,
            String canonical,
            List<String> functionCodes,
            String seniorityCode) {
        public TitleRule {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            functionCodes = functionCodes == null ? List.of() : List.copyOf(functionCodes);
        }
    }

    public record CompanyRule(
            List<String> aliases, String canonical, List<String> industryCodes, String basis) {
        public CompanyRule {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            industryCodes = industryCodes == null ? List.of() : List.copyOf(industryCodes);
        }
    }
}
