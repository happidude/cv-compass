package com.cvcompass.backend.mapping.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite literal configuration only; this version is readable but not executable yet. */
public record PersonalInfoDefinition(
        int schemaVersion,
        String engineCompatibility,
        String revision,
        ExperienceDefinition experience,
        Map<String, FieldRule> personalFields,
        Map<String, List<String>> sectionHeadings,
        List<String> excludedRegions)
        implements MappingConfig {
    public static final String ENGINE_VERSION = "personal-info-mapping/3.0";
    public static final Set<String> FIELD_NAMES =
            Set.of("fullName", "email", "phone", "location", "summary", "gender");
    public static final Set<String> SECTIONS =
            Set.of("PERSONAL", "CONTACT", "SUMMARY", "EXPERIENCE", "EDUCATION", "REFERENCE");
    private static final Set<String> PRIORITIES =
            Set.of("EXPLICIT_LABEL", "PROFILE_HEADER", "SECTION_CONTEXT");

    public PersonalInfoDefinition {
        personalFields =
                personalFields == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.TreeMap<>(personalFields));
        if (sectionHeadings == null) sectionHeadings = Map.of();
        else {
            var ordered = new java.util.TreeMap<String, List<String>>();
            sectionHeadings.forEach((key, value) -> ordered.put(key, List.copyOf(value)));
            sectionHeadings = java.util.Collections.unmodifiableMap(ordered);
        }
        excludedRegions = excludedRegions == null ? List.of() : List.copyOf(excludedRegions);
    }

    @Override
    public void validate() {
        if (schemaVersion != 3
                || !ENGINE_VERSION.equals(engineCompatibility)
                || revision == null
                || !revision.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || experience == null
                || !personalFields.keySet().equals(FIELD_NAMES)
                || !sectionHeadings.keySet().equals(SECTIONS)
                || excludedRegions.isEmpty()
                || new HashSet<>(excludedRegions).size() != excludedRegions.size()
                || !Set.of("REFERENCE", "RECRUITER", "COMPANY").containsAll(excludedRegions))
            invalid();
        experience.validate();
        for (var entry : personalFields.entrySet()) {
            FieldRule field = entry.getValue();
            names(field.labels());
            if (field.priorities().isEmpty()
                    || field.priorities().size() > 3
                    || new HashSet<>(field.priorities()).size() != field.priorities().size()
                    || !PRIORITIES.containsAll(field.priorities())) invalid();
            if (entry.getKey().equals("gender")
                    && (!field.priorities().equals(List.of("EXPLICIT_LABEL"))
                            || !field.labels().stream()
                                    .map(ExperienceDefinition::key)
                                    .collect(java.util.stream.Collectors.toSet())
                                    .equals(Set.of("gender", "sex", "giới tính", "phái"))))
                invalid();
        }
        for (var headings : sectionHeadings.values()) names(headings);
    }

    private static void names(List<String> values) {
        if (values.isEmpty() || values.size() > 32) invalid();
        var seen = new HashSet<String>();
        for (String value : values) {
            if (value == null
                    || value.isBlank()
                    || value.codePointCount(0, value.length()) > 256
                    || value.codePoints().anyMatch(Character::isISOControl)
                    || !seen.add(ExperienceDefinition.key(value))) invalid();
        }
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid personal information configuration");
    }

    public record FieldRule(boolean enabled, List<String> labels, List<String> priorities) {
        public FieldRule {
            labels = labels == null ? List.of() : List.copyOf(labels);
            priorities = priorities == null ? List.of() : List.copyOf(priorities);
        }
    }
}
