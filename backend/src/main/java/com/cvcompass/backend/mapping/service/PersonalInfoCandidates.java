package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class PersonalInfoCandidates {
    record Candidate(String raw, List<Evidence> evidence, boolean uncertain) {}

    static List<Candidate> collect(
            String field,
            Extraction input,
            PersonalInfoDefinition config,
            List<PersonalInfoRegions.Line> lines) {
        var result = new ArrayList<Candidate>();
        var rule = config.personalFields().get(field);
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (!PersonalInfoRegions.personal(line, config)) continue;
            int inlineSummaryStart =
                    field.equals("summary") ? inlineSummaryStart(line.text(), config) : -1;
            if (field.equals("summary")
                    && line.heading()
                    && line.region().equals("SUMMARY")
                    && (rule.priorities().contains("SECTION_CONTEXT")
                            || inlineSummaryStart >= 0
                                    && rule.priorities().contains("EXPLICIT_LABEL"))) {
                var spans = new ArrayList<Evidence>();
                var text = new StringBuilder();
                boolean uncertain = line.uncertain();
                if (inlineSummaryStart >= 0) {
                    String value = line.text().substring(inlineSummaryStart).stripTrailing();
                    text.append(value);
                    spans.add(
                            span(
                                    input,
                                    line,
                                    inlineSummaryStart,
                                    inlineSummaryStart + value.length()));
                }
                for (int next = index + 1; next < lines.size(); next++) {
                    var part = lines.get(next);
                    if (part.heading()) break;
                    if (part.page() != line.page()) {
                        // A page boundary may contain a repeated header/footer; do not publish a
                        // truncated paragraph.
                        uncertain = true;
                        break;
                    }
                    if (part.text().isBlank()) {
                        if (!text.isEmpty()) text.append('\n');
                        continue;
                    }
                    if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n')
                        text.append('\n');
                    text.append(part.text());
                    spans.add(span(input, part, 0, part.text().length()));
                    uncertain |= part.uncertain();
                }
                if (!text.toString().isBlank())
                    result.add(new Candidate(text.toString().strip(), spans, uncertain));
                continue;
            }
            if (line.heading()) continue;
            boolean labelled = false;
            if (rule.priorities().contains("EXPLICIT_LABEL")) {
                for (String label : rule.labels()) {
                    String key =
                            com.cvcompass.backend.mapping.dto.ExperienceDefinition.key(line.text());
                    if (key.equals(
                                    com.cvcompass.backend.mapping.dto.ExperienceDefinition.key(
                                                    label)
                                            + ":")
                            && index + 1 < lines.size()) {
                        var next = lines.get(index + 1);
                        if (next.page() == line.page()
                                && !next.heading()
                                && !next.text().isBlank()
                                && !next.text().contains(":")
                                && PersonalInfoRegions.personal(next, config)) {
                            String value = next.text().strip();
                            if (field.equals("fullName"))
                                value = PersonalInfoValidation.name(value);
                            int start = next.text().indexOf(value);
                            result.add(
                                    new Candidate(
                                            value,
                                            List.of(
                                                    span(
                                                            input,
                                                            next,
                                                            start,
                                                            start + value.length())),
                                            line.uncertain() || next.uncertain()));
                            labelled = true;
                            break;
                        }
                    }
                    var matcher =
                            Pattern.compile(
                                            "^\\s*"
                                                    + Pattern.quote(
                                                            java.text.Normalizer.normalize(
                                                                    label,
                                                                    java.text.Normalizer.Form.NFC))
                                                    + "(?:\\s*[:：]\\s*|\\s+-\\s+|\\s+)(\\S.*?)\\s*$",
                                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                                    .matcher(
                                            java.text.Normalizer.normalize(
                                                    line.text(), java.text.Normalizer.Form.NFC));
                    if (!matcher.matches()) continue;
                    labelled = true;
                    int start = originalIndex(line.text(), matcher.start(1));
                    int end = originalIndex(line.text(), matcher.end(1));
                    String value = line.text().substring(start, end);
                    if (field.equals("fullName")) {
                        String stripped = PersonalInfoValidation.name(value);
                        start += value.length() - stripped.length();
                        value = stripped;
                    }
                    if ((field.equals("email") && (value.endsWith("@") || value.endsWith("."))
                                    || field.equals("phone") && value.endsWith("-"))
                            && index + 1 < lines.size()) {
                        var next = lines.get(index + 1);
                        if (next.page() == line.page()
                                && !next.heading()
                                && next.text()
                                        .strip()
                                        .matches(
                                                field.equals("email")
                                                        ? "[A-Za-z0-9.-]+"
                                                        : "[0-9 -]+")
                                && PersonalInfoRegions.personal(next, config)) {
                            String continuation = next.text().strip();
                            int continuationStart = next.text().indexOf(continuation);
                            result.add(
                                    new Candidate(
                                            value + "\n" + continuation,
                                            List.of(
                                                    span(
                                                            input,
                                                            line,
                                                            start,
                                                            start + value.length()),
                                                    span(
                                                            input,
                                                            next,
                                                            continuationStart,
                                                            continuationStart
                                                                    + continuation.length())),
                                            line.uncertain() || next.uncertain()));
                            break;
                        }
                    }
                    result.add(
                            new Candidate(
                                    value,
                                    List.of(span(input, line, start, start + value.length())),
                                    line.uncertain()));
                    break;
                }
            }
            if (labelled || field.equals("gender") || field.equals("summary")) continue;
            if (rule.priorities().contains("SECTION_CONTEXT")
                    && List.of("PERSONAL", "CONTACT").contains(line.region())) {
                Pattern pattern =
                        switch (field) {
                            case "email" -> Pattern.compile("(?<!\\S)[^\\s<>]+@[^\\s<>]+(?!\\S)");
                            case "phone" ->
                                    !line.region().equals("CONTACT")
                                            ? null
                                            : Pattern.compile(
                                                    "^\\s*(\\+?(?:[0-9]|\\([0-9]+\\))[0-9(). /-]{6,}(?:\\s*(?i:ext\\.?|x)\\s*[0-9]+)?)\\s*$");
                            default -> null;
                        };
                if (pattern != null) {
                    var match = pattern.matcher(line.text());
                    while (match.find()) {
                        int group = field.equals("phone") ? 1 : 0;
                        result.add(
                                new Candidate(
                                        match.group(group),
                                        List.of(
                                                span(
                                                        input,
                                                        line,
                                                        match.start(group),
                                                        match.end(group))),
                                        line.uncertain()));
                    }
                }
            }
            if (field.equals("fullName")
                    && rule.priorities().contains("PROFILE_HEADER")
                    && line.region().equals("PERSONAL")
                    && index < 5
                    && !line.text().contains(":")) {
                // Unlabelled names require adjacent candidate-owned contact evidence, never a file
                // name.
                boolean contact =
                        lines.stream()
                                .anyMatch(
                                        other ->
                                                other.page() == line.page()
                                                        && other.region().equals("PERSONAL")
                                                        && other.text().contains("@"));
                String name = PersonalInfoValidation.name(line.text().strip());
                if (contact && PersonalInfoValidation.check("fullName", name).issue() == null) {
                    int start = line.text().indexOf(name);
                    result.add(
                            new Candidate(
                                    name,
                                    List.of(span(input, line, start, start + name.length())),
                                    line.uncertain()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Evidence span(
            Extraction input, PersonalInfoRegions.Line line, int start, int end) {
        return new Evidence(
                input.id(),
                line.page(),
                line.start() + line.text().codePointCount(0, start),
                line.start() + line.text().codePointCount(0, end),
                line.text().substring(start, end));
    }

    private static int inlineSummaryStart(String raw, PersonalInfoDefinition config) {
        for (String label : PersonalInfoRegions.summaryLabels(config)) {
            var matcher =
                    Pattern.compile(
                                    "^\\s*"
                                            + Pattern.quote(
                                                    java.text.Normalizer.normalize(
                                                            label, java.text.Normalizer.Form.NFC))
                                            + "\\s*[:：]\\s*(\\S.*?)\\s*$",
                                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(
                                    java.text.Normalizer.normalize(
                                            raw, java.text.Normalizer.Form.NFC));
            if (matcher.matches()) return originalIndex(raw, matcher.start(1));
        }
        return -1;
    }

    private static int originalIndex(String raw, int normalizedIndex) {
        for (int index = 0; index <= raw.length(); ) {
            if (java.text.Normalizer.normalize(
                                    raw.substring(0, index), java.text.Normalizer.Form.NFC)
                            .length()
                    >= normalizedIndex) {
                // Include combining marks belonging to the last normalized character.
                while (index < raw.length()
                        && java.lang.Character.getType(raw.codePointAt(index))
                                == Character.NON_SPACING_MARK)
                    index += Character.charCount(raw.codePointAt(index));
                return index;
            }
            if (index == raw.length()) break;
            index += Character.charCount(raw.codePointAt(index));
        }
        throw new IllegalArgumentException("Invalid normalized span");
    }
}
