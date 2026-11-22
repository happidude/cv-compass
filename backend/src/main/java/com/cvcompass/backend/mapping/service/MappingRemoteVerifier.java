package com.cvcompass.backend.mapping.service;

import static com.cvcompass.backend.mapping.remote.MappingRemoteException.require;
import static com.cvcompass.backend.mapping.remote.MappingRemoteJson.exact;
import static com.cvcompass.backend.mapping.remote.MappingRemoteJson.nullable;
import static com.cvcompass.backend.mapping.remote.MappingRemoteJson.string;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Company;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Experience;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Issue;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PartialDate;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Period;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Title;
import com.cvcompass.backend.mapping.dto.LlmExtractionDefinition;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.MappingResult.Value;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.PersonalField;
import com.cvcompass.backend.mapping.remote.MappingRemoteException;
import com.cvcompass.backend.mapping.remote.MappingRemoteJson;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Validates untrusted compact output; only server-owned identities reach the v3 envelope. */
@Service
public class MappingRemoteVerifier {
    // OTHER_TRUNCATED is worker-owned and deliberately absent from this set.
    private static final Set<String> SERVER_ISSUE_CODES =
            Set.of(
                    "FIELD_DISABLED",
                    "GENDER_UNSUPPORTED",
                    "VALUE_LENGTH_INVALID",
                    "UNRECOGNIZED_GENDER",
                    "INVALID_EMAIL",
                    "INVALID_PHONE",
                    "PHONE_MULTIPLE_FIRST_KEPT",
                    "INVALID_NAME",
                    "LEGACY_UNPAGED",
                    "OCR_UNUSABLE",
                    "NO_OK_PAGES",
                    "INVALID_RESULT",
                    "INVALID_EVIDENCE",
                    "INPUT_CHANGED",
                    "UNSUPPORTED_PROTOCOL",
                    "LEASE_EXPIRED",
                    "HARD_DEADLINE_EXCEEDED",
                    "STALE_ATTEMPT",
                    "INVALID_LEASE_TOKEN",
                    "IDEMPOTENCY_CONFLICT",
                    "OUTCOME_CONFLICT",
                    "RESULT_TOO_LARGE",
                    "MAPPING_REMOTE_UNAVAILABLE",
                    "UNAUTHORIZED",
                    "TLS_REQUIRED",
                    "RESULT_ACCEPTED",
                    "UNSUPPORTED_MEDIA_TYPE");

    public PersonalInfoMappingResult verify(
            MappingClaim claim,
            Extraction input,
            String inputHash,
            JsonNode body,
            JsonNode provenance) {
        try {
            return build(claim, input, inputHash, body, provenance);
        } catch (MappingRemoteException safe) {
            throw safe;
        } catch (RuntimeException invalid) {
            throw new MappingRemoteException(422, "INVALID_RESULT");
        }
    }

    private PersonalInfoMappingResult build(
            MappingClaim claim,
            Extraction input,
            String inputHash,
            JsonNode body,
            JsonNode provenance) {
        exact(
                body,
                Set.of(
                        "protocol",
                        "inputSha256",
                        "personal",
                        "experiences",
                        "other",
                        "issues",
                        "provenance"));
        MappingRemoteJson.protocol(body);
        require(string(body.path("inputSha256")).matches("[0-9a-f]{64}"), 422, "INVALID_RESULT");
        require(inputHash.equals(body.path("inputSha256").asString()), 409, "INPUT_CHANGED");
        require(
                MappingRemoteJson.provenance(body.path("provenance")).equals(provenance),
                422,
                "INVALID_RESULT");
        var definition = (LlmExtractionDefinition) claim.definition();
        exact(body.path("personal"), PersonalInfoDefinition.V33_FIELD_NAMES);
        var issues = new ArrayList<Issue>();
        var personal = new TreeMap<String, PersonalField>();
        for (String field : PersonalInfoDefinition.V33_FIELD_NAMES) {
            JsonNode node = body.path("personal").path(field);
            String raw = null;
            var evidence = new ArrayList<Evidence>();
            var fieldIssues = new ArrayList<Issue>();
            if (!node.isNull()) {
                exact(node, Set.of("value", "evidence"));
                raw = string(node.path("value"));
                JsonNode spans = node.path("evidence");
                if (field.equals("summary") && spans.isArray()) {
                    require(
                            !raw.isBlank() && spans.size() >= 1 && spans.size() <= 8,
                            422,
                            "INVALID_EVIDENCE");
                    var texts = new ArrayList<String>();
                    Evidence previous = null;
                    for (JsonNode span : spans) {
                        Evidence current = evidence(input, span, null);
                        require(
                                previous == null
                                        || previous.page() < current.page()
                                        || previous.page() == current.page()
                                                && previous.end() <= current.start(),
                                422,
                                "INVALID_EVIDENCE");
                        evidence.add(current);
                        texts.add(current.text());
                        previous = current;
                    }
                    require(
                            whitespace(raw).equals(whitespace(String.join(" ", texts))),
                            422,
                            "INVALID_EVIDENCE");
                } else {
                    require(!spans.isArray(), 422, "INVALID_EVIDENCE");
                    require(!raw.isBlank(), 422, "INVALID_RESULT");
                    evidence.add(
                            evidence(
                                    input,
                                    spans,
                                    Set.of("gender", "dateOfBirth").contains(field) ? null : raw));
                }
                if (field.equals("gender"))
                    require(Set.of("MALE", "FEMALE", "OTHER").contains(raw), 422, "INVALID_RESULT");
                if (field.equals("dateOfBirth")) birthDate(raw);
            }
            boolean enabled = definition.fieldDefinitions().get(field).enabled();
            String normalized = null;
            if (!enabled) {
                raw = null;
                evidence.clear();
                fieldIssues.add(new Issue("FIELD_DISABLED", field, List.of()));
            } else if (raw != null) {
                var checked = PersonalInfoValidation.checkRemote(field, raw);
                normalized = checked.normalized();
                String code =
                        field.equals("gender") && raw.equals("OTHER")
                                ? "GENDER_UNSUPPORTED"
                                : checked.issue();
                if (code != null) fieldIssues.add(new Issue(code, field, evidence));
                if (checked.info() != null)
                    fieldIssues.add(new Issue(checked.info(), field, evidence));
            }
            personal.put(
                    field,
                    new PersonalField(
                            enabled,
                            raw,
                            normalized,
                            raw == null ? "NOT_FOUND" : "PROPOSED",
                            List.of(),
                            evidence,
                            fieldIssues));
        }
        JsonNode rows = body.path("experiences");
        require(rows.isArray() && rows.size() <= 50, 422, "INVALID_RESULT");
        var experiences = new ArrayList<Experience>();
        for (JsonNode row : rows) {
            exact(row, Set.of("title", "company", "start", "end", "ongoing", "evidence"));
            String title = nullable(row.path("title")), company = nullable(row.path("company"));
            String start = nullable(row.path("start")), end = nullable(row.path("end"));
            require(row.path("ongoing").isBoolean(), 422, "INVALID_RESULT");
            boolean ongoing = row.path("ongoing").asBoolean();
            var keys = new HashSet<String>();
            if (title != null) keys.add("title");
            if (company != null) keys.add("company");
            if (start != null || end != null || ongoing) keys.add("period");
            exact(row.path("evidence"), keys);
            var titleEvidence =
                    title == null
                            ? List.<Evidence>of()
                            : List.of(evidence(input, row.path("evidence").path("title"), title));
            var companyEvidence =
                    company == null
                            ? List.<Evidence>of()
                            : List.of(
                                    evidence(input, row.path("evidence").path("company"), company));
            var periodEvidence =
                    !keys.contains("period")
                            ? List.<Evidence>of()
                            : List.of(evidence(input, row.path("evidence").path("period"), null));
            PartialDate from = date(start, 1950), to = date(end, 1950);
            require(!ongoing || end == null, 422, "INVALID_RESULT");
            require(
                    from == null || to == null || !from.earliest().isAfter(to.latest()),
                    422,
                    "INVALID_RESULT");
            var all = new ArrayList<Evidence>();
            all.addAll(titleEvidence);
            all.addAll(companyEvidence);
            all.addAll(periodEvidence);
            experiences.add(
                    new Experience(
                            "llm-" + (experiences.size() + 1),
                            new Company(
                                    company,
                                    null,
                                    company == null ? "NOT_FOUND" : "PROPOSED",
                                    List.of(),
                                    "NOT_FOUND",
                                    null,
                                    companyEvidence),
                            new Title(
                                    title,
                                    null,
                                    title == null ? "NOT_FOUND" : "PROPOSED",
                                    List.of(),
                                    "NOT_FOUND",
                                    null,
                                    titleEvidence,
                                    List.of()),
                            new Period(
                                    from,
                                    to,
                                    ongoing,
                                    periodEvidence.isEmpty() ? "NOT_FOUND" : "PROPOSED",
                                    List.of(),
                                    periodEvidence),
                            "",
                            List.of(),
                            all,
                            List.of()));
        }
        if (!definition.fieldDefinitions().get("experiences").enabled()) {
            experiences.clear();
            issues.add(new Issue("FIELD_DISABLED", "experiences", List.of()));
        }
        var values = new ArrayList<Value>();
        JsonNode other = body.path("other");
        require(
                other.isArray()
                        && other.size()
                                <= (definition.other().enabled()
                                        ? definition.other().maxItems()
                                        : 0),
                422,
                "INVALID_RESULT");
        for (JsonNode row : other) {
            exact(row, Set.of("label", "value", "evidence"));
            String label = string(row.path("label")), value = string(row.path("value"));
            require(!label.isBlank() && !value.isBlank(), 422, "INVALID_RESULT");
            values.add(
                    new Value(
                            "other:" + label, value, evidence(input, row.path("evidence"), value)));
        }
        JsonNode reported = body.path("issues");
        require(reported.isArray() && reported.size() <= 200, 422, "INVALID_RESULT");
        for (JsonNode row : reported) {
            exact(row, Set.of("code", "field"));
            String code = string(row.path("code"));
            require(code.matches("[A-Z][A-Z0-9_]{0,63}"), 422, "INVALID_RESULT");
            require(!SERVER_ISSUE_CODES.contains(code), 422, "INVALID_RESULT");
            String field = nullable(row.path("field"));
            require(
                    field == null
                            || PersonalInfoDefinition.V33_FIELD_NAMES.contains(field)
                            || Set.of("experiences", "other").contains(field),
                    422,
                    "INVALID_RESULT");
            issues.add(new Issue(code, field, List.of()));
        }
        boolean incomplete =
                !"success".equals(input.status())
                        || input.pages().stream()
                                .anyMatch(
                                        p ->
                                                List.of("failed", "not_processed")
                                                        .contains(p.status()));
        String reason =
                input.legacy()
                        ? "LEGACY_UNPAGED"
                        : !List.of("success", "partial").contains(input.status())
                                ? "OCR_UNUSABLE"
                                : input.pages().stream().noneMatch(p -> "ok".equals(p.status()))
                                        ? "NO_OK_PAGES"
                                        : null;
        return new PersonalInfoMappingResult(
                3,
                input.id(),
                input.schemaVersion(),
                claim.configId(),
                claim.configRevision(),
                LlmExtractionDefinition.ENGINE_VERSION,
                "NEEDS_REVIEW",
                incomplete,
                reason,
                values,
                inputHash,
                input.documentId(),
                input.profileRevision(),
                definition.revision(),
                "none",
                experiences,
                issues,
                personal);
    }

    private static Evidence evidence(Extraction input, JsonNode node, String value) {
        exact(node, Set.of("page", "start", "end", "text"));
        for (String key : List.of("page", "start", "end"))
            require(
                    node.path(key).isIntegralNumber() && node.path(key).canConvertToInt(),
                    422,
                    "INVALID_EVIDENCE");
        int page = node.path("page").asInt(),
                start = node.path("start").asInt(),
                end = node.path("end").asInt();
        require(node.path("text").isString(), 422, "INVALID_EVIDENCE");
        String text = node.path("text").asString();
        String raw =
                input.pages().stream()
                        .filter(p -> p.number() == page)
                        .map(p -> p.rawText() == null ? "" : p.rawText())
                        .findFirst()
                        .orElse(null);
        require(
                raw != null
                        && start >= 0
                        && start < end
                        && end <= raw.codePointCount(0, raw.length()),
                422,
                "INVALID_EVIDENCE");
        require(
                text.equals(
                        raw.substring(
                                raw.offsetByCodePoints(0, start), raw.offsetByCodePoints(0, end))),
                422,
                "INVALID_EVIDENCE");
        require(
                value == null || !value.isBlank() && whitespace(value).equals(whitespace(text)),
                422,
                "INVALID_EVIDENCE");
        return new Evidence(input.id(), page, start, end, text);
    }

    private static String whitespace(String value) {
        return value.replaceAll("(?U)\\s+", " ").strip();
    }

    // Birth dates may carry a day; experience periods stay YYYY/YYYY-MM only (see date()).
    private static void birthDate(String value) {
        require(
                value.matches("[0-9]{4}(?:-(?:0[1-9]|1[0-2])(?:-[0-9]{2})?)?"),
                422,
                "INVALID_RESULT");
        if (value.length() < 10) {
            date(value, 1900);
            return;
        }
        int year = Integer.parseInt(value.substring(0, 4));
        require(year >= 1900 && year <= 2100, 422, "INVALID_RESULT");
        boolean valid = true;
        try {
            LocalDate.of(
                    year,
                    Integer.parseInt(value.substring(5, 7)),
                    Integer.parseInt(value.substring(8)));
        } catch (DateTimeException e) {
            valid = false;
        }
        require(valid, 422, "INVALID_RESULT");
    }

    private static PartialDate date(String value, int minimum) {
        if (value == null) return null;
        require(value.matches("[0-9]{4}(?:-(?:0[1-9]|1[0-2]))?"), 422, "INVALID_RESULT");
        int year = Integer.parseInt(value.substring(0, 4));
        require(year >= minimum && year <= 2100, 422, "INVALID_RESULT");
        return new PartialDate(
                year,
                value.length() == 4 ? null : Integer.valueOf(value.substring(5)),
                null,
                value.length() == 4 ? "YEAR" : "MONTH");
    }
}
