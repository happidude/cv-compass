package com.cvcompass.backend.mapping.dto;

import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.MappingResult.Value;
import java.time.LocalDate;
import java.util.List;

public record ExperienceMappingResult(
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
        List<Issue> issues)
        implements MappingOutcome {
    public ExperienceMappingResult {
        values = List.copyOf(values);
        experiences = List.copyOf(experiences);
        issues = List.copyOf(issues);
    }

    public record Issue(String code, String field, List<Evidence> evidence) {
        public Issue {
            evidence = List.copyOf(evidence);
        }
    }

    public record PartialDate(int year, Integer month, Integer day, String precision) {
        public LocalDate earliest() {
            return LocalDate.of(year, month == null ? 1 : month, day == null ? 1 : day);
        }

        public LocalDate latest() {
            return day != null
                    ? earliest()
                    : month != null
                            ? java.time.YearMonth.of(year, month).atEndOfMonth()
                            : LocalDate.of(year, 12, 31);
        }
    }

    public record Company(
            String raw,
            String normalized,
            String state,
            List<String> industryCodes,
            String industryState,
            String industryBasis,
            List<Evidence> evidence) {
        public Company {
            industryCodes = List.copyOf(industryCodes);
            evidence = List.copyOf(evidence);
        }
    }

    public record Title(
            String raw,
            String canonical,
            String state,
            List<String> functionCodes,
            String functionState,
            String seniorityCode,
            List<Evidence> evidence,
            List<String> alternatives) {
        public Title {
            functionCodes = List.copyOf(functionCodes);
            evidence = List.copyOf(evidence);
            alternatives = List.copyOf(alternatives);
        }
    }

    public record PeriodProposal(
            PartialDate start,
            PartialDate end,
            boolean ongoingAsStated,
            String raw,
            String state,
            List<Evidence> evidence) {
        public PeriodProposal {
            evidence = List.copyOf(evidence);
        }
    }

    public record Period(
            PartialDate start,
            PartialDate end,
            boolean ongoingAsStated,
            String state,
            List<PeriodProposal> alternatives,
            List<Evidence> evidence) {
        public Period {
            alternatives = List.copyOf(alternatives);
            evidence = List.copyOf(evidence);
        }
    }

    public record Experience(
            String localId,
            Company company,
            Title title,
            Period period,
            String descriptionRaw,
            List<Evidence> descriptionEvidence,
            List<Evidence> evidence,
            List<Issue> issues) {
        public Experience {
            descriptionEvidence = List.copyOf(descriptionEvidence);
            evidence = List.copyOf(evidence);
            issues = List.copyOf(issues);
        }
    }
}
