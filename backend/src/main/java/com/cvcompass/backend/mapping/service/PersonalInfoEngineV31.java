package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Issue;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import java.util.List;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** An explicit opt-in handler keeps queued 3.0 jobs on their original implementation. */
@Service
public class PersonalInfoEngineV31 {
    public static final String VERSION = PersonalInfoDefinition.GRAMMAR_ENGINE_VERSION;

    public PersonalInfoMappingResult map(MappingClaim claim, Extraction input) {
        return map(claim, input, VERSION);
    }

    static PersonalInfoMappingResult map(MappingClaim claim, Extraction input, String version) {
        if (!(claim.definition() instanceof PersonalInfoDefinition definition)
                || !version.equals(claim.engineVersion())
                || !version.equals(definition.engineCompatibility())
                || claim.extractionId() != input.id())
            throw new IllegalArgumentException("Personal information engine/config/input mismatch");
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
        var experience =
                rejection == null
                        ? new ExperienceGrammar(input, definition.experience()).parse()
                        : null;
        var personal = new TreeMap<String, PersonalInfoMappingResult.PersonalField>();
        if (rejection == null) personal.putAll(new PersonalInfoGrammar(input, definition).parse());
        else
            for (String field : definition.fieldNames()) {
                boolean enabled = definition.personalFields().get(field).enabled();
                personal.put(
                        field,
                        new PersonalInfoMappingResult.PersonalField(
                                enabled,
                                null,
                                null,
                                "NOT_FOUND",
                                List.of(),
                                List.of(),
                                List.of(
                                        new Issue(
                                                enabled ? rejection : "FIELD_DISABLED",
                                                field,
                                                List.of()))));
            }
        var result =
                new PersonalInfoMappingResult(
                        3,
                        input.id(),
                        input.schemaVersion(),
                        claim.configId(),
                        claim.configRevision(),
                        version,
                        rejection == null ? "NEEDS_REVIEW" : "UNSUPPORTED_INPUT",
                        incomplete,
                        rejection,
                        List.of(),
                        MappingDigests.sha256(MappingDigests.canonical(input)),
                        input.documentId(),
                        input.profileRevision(),
                        definition.experience().rulesRevision(),
                        definition.experience().taxonomyRevision(),
                        experience == null ? List.of() : experience.experiences(),
                        experience == null ? List.of() : experience.issues(),
                        personal);
        var evidenceValidator = new MappingEngine();
        result.experiences()
                .forEach(
                        row ->
                                row.evidence()
                                        .forEach(
                                                span ->
                                                        evidenceValidator.validateEvidence(
                                                                input, span)));
        result.personalInfo()
                .values()
                .forEach(
                        field ->
                                field.alternatives()
                                        .forEach(
                                                value ->
                                                        value.evidence()
                                                                .forEach(
                                                                        span ->
                                                                                evidenceValidator
                                                                                        .validateEvidence(
                                                                                                input,
                                                                                                span))));
        return result;
    }
}
