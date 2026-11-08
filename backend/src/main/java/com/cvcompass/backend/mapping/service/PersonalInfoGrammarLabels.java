package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Operator aliases remain literals; only this finite boundary grammar is executable. */
final class PersonalInfoGrammarLabels {
    private static final List<String> BOUNDARIES =
            List.of(
                    "DOB",
                    "Date of birth",
                    "Day of birth",
                    "Ngày sinh",
                    "Marital status",
                    "Tình trạng hôn nhân",
                    "Nationality",
                    "Languages",
                    "Website",
                    "LinkedIn",
                    "Current salary",
                    "Expected salary",
                    "Salary",
                    "Budget",
                    "Employee ID",
                    "ID",
                    "Headcount",
                    "Reporting to",
                    "Report to",
                    "Manager",
                    "Open to relocate",
                    "Willing to relocate",
                    "Desired location",
                    "Place of birth",
                    "Place of origin",
                    "Work location",
                    "Company address",
                    "Notice period");

    record Label(String field, int start, int valueStart, int end) {}

    private record Alias(String field, String text, Pattern labelled, Pattern prefix) {}

    private final List<Alias> aliases;

    PersonalInfoGrammarLabels(PersonalInfoDefinition config) {
        var values = new ArrayList<Alias>();
        config.personalFields()
                .forEach(
                        (field, rule) ->
                                rule.labels().forEach(label -> values.add(alias(field, label))));
        boolean v33 =
                PersonalInfoDefinition.GRAMMAR_V34_ENGINE_VERSION.equals(
                                config.engineCompatibility())
                        || PersonalInfoDefinition.GRAMMAR_V33_ENGINE_VERSION.equals(
                                config.engineCompatibility());
        var configured =
                values.stream().map(Alias::text).collect(java.util.stream.Collectors.toSet());
        // 3.3 turns some boundaries (date of birth) into fields; a field label wins over the
        // anonymous boundary with the same text.
        BOUNDARIES.stream()
                .filter(label -> !v33 || !configured.contains(PersonalInfoGrammarLines.key(label)))
                .forEach(label -> values.add(alias(null, label)));
        values.sort(Comparator.comparingInt((Alias value) -> value.text().length()).reversed());
        aliases = List.copyOf(values);
    }

    List<Label> find(String text) {
        var found = new ArrayList<Label>();
        String standaloneKey = PersonalInfoGrammarLines.key(text);
        for (var alias : aliases) {
            var match = alias.labelled().matcher(text);
            while (match.find()) {
                boolean overlaps =
                        found.stream()
                                .anyMatch(
                                        old ->
                                                match.start() < old.valueStart()
                                                        && match.end() > old.start());
                if (!overlaps)
                    found.add(new Label(alias.field(), match.start(), match.end(), text.length()));
            }
            if (standaloneKey.equals(alias.text()) && found.isEmpty())
                found.add(new Label(alias.field(), 0, text.length(), text.length()));
        }
        if (found.isEmpty()) {
            for (var alias : aliases) {
                if (alias.prefix() == null) continue;
                var prefix = alias.prefix().matcher(text);
                if (prefix.find()) {
                    found.add(new Label(alias.field(), 0, prefix.end(), text.length()));
                    break;
                }
            }
        }
        found.sort(Comparator.comparingInt(Label::start));
        var result = new ArrayList<Label>();
        for (int index = 0; index < found.size(); index++) {
            var value = found.get(index);
            result.add(
                    new Label(
                            value.field(),
                            value.start(),
                            value.valueStart(),
                            index + 1 < found.size()
                                    ? found.get(index + 1).start()
                                    : text.length()));
        }
        return result;
    }

    private static Alias alias(String field, String literal) {
        String key = PersonalInfoGrammarLines.key(literal);
        return new Alias(
                field,
                key,
                Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(key) + "\\s*[:：]"),
                field == null || field.equals("summary")
                        ? null
                        : Pattern.compile("(?iu)^" + Pattern.quote(key) + "\\s+(?=\\S)"));
    }

    boolean isBoundary(String text) {
        return !find(text).isEmpty();
    }
}
