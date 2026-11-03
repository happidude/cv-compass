package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Issue;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.Alternative;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.PersonalField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class PersonalInfoEngine {
    public static final String VERSION = PersonalInfoDefinition.ENGINE_VERSION;
    private final ExperienceEngine experience;

    public PersonalInfoEngine(ExperienceEngine experience) {
        this.experience = experience;
    }

    public PersonalInfoMappingResult map(MappingClaim claim, Extraction input) {
        if (!(claim.definition() instanceof PersonalInfoDefinition definition)
                || !VERSION.equals(claim.engineVersion())
                || claim.extractionId() != input.id())
            throw new IllegalArgumentException("Personal information engine/config/input mismatch");
        definition.validate();
        var legacyClaim =
                new MappingClaim(
                        claim.jobId(),
                        claim.extractionId(),
                        claim.configId(),
                        claim.configRevision(),
                        ExperienceEngine.VERSION,
                        claim.attempt(),
                        claim.token(),
                        claim.leaseExpiresAt(),
                        definition.experience());
        var old = experience.map(legacyClaim, input);
        var lines = PersonalInfoRegions.read(input, definition);
        var fields = new TreeMap<String, PersonalField>();
        for (String field : PersonalInfoDefinition.FIELD_NAMES) {
            if (!definition.personalFields().get(field).enabled()) {
                fields.put(
                        field,
                        new PersonalField(
                                false,
                                null,
                                null,
                                "NOT_FOUND",
                                List.of(),
                                List.of(),
                                List.of(new Issue("FIELD_DISABLED", field, List.of()))));
            } else if (old.reason() != null) {
                fields.put(
                        field,
                        new PersonalField(
                                true,
                                null,
                                null,
                                "NOT_FOUND",
                                List.of(),
                                List.of(),
                                List.of(new Issue(old.reason(), field, List.of()))));
            } else {
                fields.put(
                        field,
                        render(
                                field,
                                PersonalInfoCandidates.collect(field, input, definition, lines)));
            }
        }
        var validator = new MappingEngine();
        fields.values()
                .forEach(
                        field ->
                                field.alternatives()
                                        .forEach(
                                                alternative ->
                                                        alternative
                                                                .evidence()
                                                                .forEach(
                                                                        span ->
                                                                                validator
                                                                                        .validateEvidence(
                                                                                                input,
                                                                                                span))));
        return new PersonalInfoMappingResult(
                3,
                old.extractionId(),
                old.ocrSchemaVersion(),
                old.configId(),
                old.configRevision(),
                VERSION,
                old.quality(),
                old.incompleteSource(),
                old.reason(),
                old.values(),
                old.inputSha256(),
                old.documentId(),
                old.profileRevision(),
                old.rulesRevision(),
                old.taxonomyRevision(),
                old.experiences(),
                old.issues(),
                fields);
    }

    private PersonalField render(String field, List<PersonalInfoCandidates.Candidate> candidates) {
        var alternatives = new ArrayList<Alternative>();
        var issues = new ArrayList<Issue>();
        var valid = new LinkedHashMap<String, List<PersonalInfoCandidates.Candidate>>();
        boolean uncertain = false;
        for (var candidate : candidates) {
            var checked = PersonalInfoValidation.check(field, candidate.raw());
            alternatives.add(
                    new Alternative(candidate.raw(), checked.normalized(), candidate.evidence()));
            if (checked.issue() != null)
                issues.add(new Issue(checked.issue(), field, candidate.evidence()));
            else
                valid.computeIfAbsent(checked.normalized(), ignored -> new ArrayList<>())
                        .add(candidate);
            uncertain |= candidate.uncertain();
        }
        var unique = new LinkedHashMap<String, Alternative>();
        for (var alternative : alternatives) {
            var previous = unique.get(alternative.raw());
            if (previous == null) unique.put(alternative.raw(), alternative);
            else {
                var spans = new ArrayList<>(previous.evidence());
                spans.addAll(alternative.evidence());
                unique.put(
                        alternative.raw(),
                        new Alternative(alternative.raw(), alternative.normalized(), spans));
            }
        }
        alternatives = new ArrayList<>(unique.values());
        String state =
                candidates.isEmpty()
                        ? "NOT_FOUND"
                        : uncertain || valid.size() > 1
                                ? "AMBIGUOUS"
                                : valid.isEmpty() ? "INVALID" : "PROPOSED";
        if (uncertain) issues.add(new Issue("READING_ORDER_UNVERIFIED", field, List.of()));
        if (valid.size() > 1) issues.add(new Issue("CONFLICTING_VALUES", field, List.of()));
        if (!state.equals("PROPOSED"))
            return new PersonalField(true, null, null, state, alternatives, List.of(), issues);
        var selected = valid.entrySet().iterator().next();
        var evidence = new ArrayList<Evidence>();
        selected.getValue().forEach(candidate -> evidence.addAll(candidate.evidence()));
        return new PersonalField(
                true,
                selected.getValue().getFirst().raw(),
                selected.getKey(),
                state,
                alternatives,
                evidence,
                issues);
    }
}
