package com.cvcompass.backend.mapping.dto;

import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Experience;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Issue;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.MappingResult.Value;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A distinct v3 envelope reuses v2 experiences without widening the v2 schema gate. */
public record PersonalInfoMappingResult(
        int schemaVersion,
        long extractionId,
        String ocrSchemaVersion,
        long configId,
        String configRevision,
        String engineVersion,
        String quality,
        boolean incompleteSource,
        String reason,
        List<Value> values,
        String inputSha256,
        long documentId,
        String profileRevision,
        String rulesRevision,
        String taxonomyRevision,
        List<Experience> experiences,
        List<Issue> issues,
        Map<String, PersonalField> personalInfo)
        implements MappingOutcome {
    public PersonalInfoMappingResult {
        if (schemaVersion != 3
                || !PersonalInfoDefinition.ENGINE_VERSION.equals(engineVersion)
                || personalInfo == null
                || !personalInfo.keySet().equals(PersonalInfoDefinition.FIELD_NAMES)) invalid();
        values = List.copyOf(values);
        experiences = List.copyOf(experiences);
        issues = List.copyOf(issues);
        // Stable v3 map ordering avoids changing digests across JVM restarts.
        personalInfo = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(personalInfo));
        PersonalField gender = personalInfo.get("gender");
        genderValue(gender.normalized());
        for (Alternative alternative : gender.alternatives()) genderValue(alternative.normalized());
        if (gender.raw() != null && gender.normalized() == null && gender.issues().isEmpty())
            invalid();
    }

    private static void genderValue(String value) {
        if (value != null && !Set.of("MALE", "FEMALE").contains(value)) invalid();
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid personal information result");
    }

    public record PersonalField(
            boolean enabled,
            String raw,
            String normalized,
            String state,
            List<Alternative> alternatives,
            List<Evidence> evidence,
            List<Issue> issues) {
        public PersonalField {
            alternatives = List.copyOf(alternatives);
            evidence = List.copyOf(evidence);
            issues = List.copyOf(issues);
            if (!Set.of("PROPOSED", "NOT_FOUND", "AMBIGUOUS", "INVALID").contains(state)
                    || "PROPOSED".equals(state) && (raw == null || raw.isBlank())
                    || !"PROPOSED".equals(state) && normalized != null
                    || !enabled
                            && (!"NOT_FOUND".equals(state)
                                    || raw != null
                                    || issues.stream()
                                            .noneMatch(
                                                    issue ->
                                                            "FIELD_DISABLED".equals(issue.code()))))
                invalid();
        }
    }

    public record Alternative(String raw, String normalized, List<Evidence> evidence) {
        public Alternative {
            if (raw == null || raw.isBlank()) invalid();
            evidence = List.copyOf(evidence);
        }
    }
}
