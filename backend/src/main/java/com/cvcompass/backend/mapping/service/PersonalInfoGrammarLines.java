package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Source lines remain immutable; heading canonicalization never rewrites value spans. */
final class PersonalInfoGrammarLines {
    private static final Pattern PAGE_NOISE =
            Pattern.compile(
                    "(?iu)(?:curriculum vita[eæ]|personal resume|resume|cv|page\\s+\\d+(?:\\s+(?:of|/)\\s*\\d+)?|\\d{1,3}(?:\\s*/\\s*\\d{1,3})?)");
    private static final Set<String> EXCLUDED =
            Set.of("REFERENCE", "RECRUITER", "COMPANY", "EXPERIENCE", "EDUCATION", "COVER_LETTER");
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern TRAILING_COLON = Pattern.compile("[:：]$");
    private static final Pattern SPACED_HEADING = Pattern.compile("(?U)(?:\\p{L}\\s+){2,}\\p{L}");
    private static final Pattern CONTINUED =
            Pattern.compile("(?iu)\\s*\\((?:continued|tiếp theo)\\)\\s*[:：]?$");
    private static final Pattern GREETING = Pattern.compile("(?iu)(?:dear\\b.*|kính gửi\\b.*)");
    private static final Pattern RECRUITER =
            Pattern.compile("(?iu)(?:recruiter|recruitment contact|nhà tuyển dụng)(?:\\s*[:：].*)?");
    private static final Pattern COMPANY_METADATA =
            Pattern.compile(
                    "(?iu)(?:company contact|company details|thông tin công ty|employer|company|công ty)\\s*[:：].*");
    private static final Pattern COMPANY_HEADING =
            Pattern.compile("(?iu)(?:company contact|company details|thông tin công ty)");
    private static final Pattern UNPROVEN_SPACING = Pattern.compile("(?s).*\\S(?:\\t| {3,})\\S.*");

    private record Headings(
            Map<String, String> exact, Map<String, String> spaced, List<Pattern> inlineSummary) {}

    record Line(
            SourceLine source,
            int index,
            String region,
            boolean heading,
            boolean continued,
            int scope,
            boolean explicitPersonal,
            boolean warning,
            boolean repeatedMargin,
            boolean noise) {
        String text() {
            return source.text;
        }

        int page() {
            return source.page;
        }

        boolean personal() {
            return !EXCLUDED.contains(region);
        }

        boolean blank() {
            return text().isEmpty();
        }

        boolean unprovenSpacing() {
            return UNPROVEN_SPACING.matcher(source.raw).matches();
        }
    }

    static List<Line> read(Extraction input, PersonalInfoDefinition config) {
        var raw = new ArrayList<SourceLine>();
        var warnedPages = new HashSet<Integer>();
        for (var page : input.pages()) {
            if (!"ok".equals(page.status()) || page.rawText() == null) continue;
            if (input.warnings().stream().anyMatch(PersonalInfoGrammarLines::layoutWarning)
                    || page.warnings().stream().anyMatch(PersonalInfoGrammarLines::layoutWarning))
                warnedPages.add(page.number());
            int start = 0;
            for (int end = 0; end <= page.rawText().length(); end++) {
                if (end == page.rawText().length()
                        || page.rawText().charAt(end) == '\n'
                        || page.rawText().charAt(end) == '\r') {
                    raw.add(
                            new SourceLine(
                                    input.id(), page, start, page.rawText().substring(start, end)));
                    if (raw.size() > 10000)
                        throw new IllegalArgumentException(
                                "Personal information line limit exceeded");
                    if (end + 1 < page.rawText().length()
                            && page.rawText().charAt(end) == '\r'
                            && page.rawText().charAt(end + 1) == '\n') end++;
                    start = end + 1;
                }
            }
        }
        var repeated = repeatedMargins(raw);
        var headings = headings(config);
        var result = new ArrayList<Line>();
        String region = "PERSONAL";
        int scope = 0;
        boolean explicit = false;
        for (int index = 0; index < raw.size(); index++) {
            var source = raw.get(index);
            String text = source.text;
            var marker = CONTINUED.matcher(text);
            boolean continued = marker.find();
            String withoutMarker = continued ? marker.replaceFirst("") : text;
            String heading = heading(withoutMarker, headings);
            if (GREETING.matcher(text).matches()) heading = "COVER_LETTER";
            boolean ownership = List.of("REFERENCE", "RECRUITER", "COMPANY").contains(region);
            if (heading != null) {
                if (ownership && List.of("CONTACT", "SUMMARY").contains(heading)) heading = region;
                if (heading.equals("PERSONAL")) {
                    scope++;
                    explicit = true;
                } else if (!heading.equals("CONTACT") && !heading.equals("SUMMARY")) {
                    scope++;
                    explicit = false;
                }
                region = heading;
            }
            boolean noise = PAGE_NOISE.matcher(text).matches();
            result.add(
                    new Line(
                            source,
                            index,
                            region,
                            heading != null,
                            continued,
                            scope,
                            explicit,
                            warnedPages.contains(source.page),
                            repeated.contains(index),
                            noise));
        }
        return List.copyOf(result);
    }

    static String key(String text) {
        String normalized =
                java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).strip();
        return WHITESPACE
                .matcher(TRAILING_COLON.matcher(normalized).replaceFirst(""))
                .replaceAll(" ")
                .toLowerCase(Locale.ROOT);
    }

    private static Headings headings(PersonalInfoDefinition config) {
        var exact = new java.util.LinkedHashMap<String, String>();
        var spaced = new java.util.LinkedHashMap<String, String>();
        config.sectionHeadings()
                .forEach(
                        (section, aliases) ->
                                aliases.forEach(
                                        alias -> {
                                            String key = key(alias);
                                            exact.putIfAbsent(key, section);
                                            spaced.putIfAbsent(key.replace(" ", ""), section);
                                        }));
        var inline =
                config.personalFields().get("summary").labels().stream()
                        .map(
                                literal ->
                                        Pattern.compile(
                                                "(?iu)^"
                                                        + Pattern.quote(key(literal))
                                                        + "\\s*[:：]\\s*\\S.*$"))
                        .toList();
        return new Headings(Map.copyOf(exact), Map.copyOf(spaced), inline);
    }

    private static String heading(String text, Headings headings) {
        String normalized = key(text);
        String exact = headings.exact().get(normalized);
        if (exact != null) return exact;
        // Only an entire letter-spaced heading may lose its inter-glyph spaces.
        if (SPACED_HEADING.matcher(normalized).matches()) {
            String spaced = headings.spaced().get(normalized.replace(" ", ""));
            if (spaced != null) return spaced;
        }
        if (headings.inlineSummary().stream()
                .anyMatch(pattern -> pattern.matcher(normalized).matches())) return "SUMMARY";
        if (RECRUITER.matcher(normalized).matches()) return "RECRUITER";
        if (COMPANY_METADATA.matcher(normalized).matches()
                || COMPANY_HEADING.matcher(normalized).matches()) return "COMPANY";
        return null;
    }

    private static boolean layoutWarning(String warning) {
        String value = warning.toLowerCase(Locale.ROOT);
        return value.contains("column") || value.contains("reading") || value.contains("order");
    }

    static Set<Integer> repeatedMargins(List<SourceLine> lines) {
        var byPage = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < lines.size(); i++)
            if (!lines.get(i).text.isEmpty())
                byPage.computeIfAbsent(lines.get(i).page, ignored -> new ArrayList<>()).add(i);
        var occurrences = new HashMap<String, List<Integer>>();
        for (var page : byPage.values())
            for (int i = 0; i < page.size(); i++)
                if (i < 2 || i >= page.size() - 2)
                    occurrences
                            .computeIfAbsent(
                                    lines.get(page.get(i)).text, ignored -> new ArrayList<>())
                            .add(page.get(i));
        var result = new HashSet<Integer>();
        occurrences.values().stream()
                .filter(
                        indices ->
                                indices.stream().map(i -> lines.get(i).page).distinct().count() > 1)
                .forEach(result::addAll);
        return result;
    }

    static Evidence range(Extraction input, List<Evidence> spans) {
        var first = spans.getFirst();
        var last = spans.getLast();
        if (first.page() != last.page())
            throw new IllegalArgumentException("Cross-page source range");
        String raw =
                input.pages().stream()
                        .filter(page -> page.number() == first.page())
                        .findFirst()
                        .orElseThrow()
                        .rawText();
        int start = raw.offsetByCodePoints(0, first.start());
        int end = raw.offsetByCodePoints(0, last.end());
        return new Evidence(
                input.id(), first.page(), first.start(), last.end(), raw.substring(start, end));
    }
}
