package com.cvcompass.backend.candidate.service;

import static com.cvcompass.backend.candidate.service.CandidateValueMapper.date;

import com.cvcompass.backend.candidate.exception.CandidateFailure;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CandidateValidator {

    public void validateBasic(JsonNode value) {
        if (value == null
                || !value.isObject()
                || !Set.of("fullName", "email", "phone", "location", "summary")
                        .containsAll(value.propertyNames())) invalid();
        for (String field : value.propertyNames())
            if (!value.get(field).isNull()
                    && (!value.get(field).isString()
                            || value.get(field).asString().length()
                                    > (field.equals("summary")
                                            ? 10000
                                            : field.equals("phone") ? 50 : 255))) invalid();
    }

    public JsonNode userExperience(JsonNode value) {
        if (value == null || !value.isObject() || value.toString().length() > 65536) invalid();
        ObjectNode clean = (ObjectNode) value.deepCopy();
        if (!Set.of(
                        "company",
                        "title",
                        "period",
                        "descriptionRaw",
                        "descriptionEvidence",
                        "evidence",
                        "issues",
                        "localId")
                .containsAll(clean.propertyNames())) invalid();
        stripEvidence(clean);
        for (String field : List.of("company", "title", "period"))
            if (!clean.path(field).isObject()) invalid();
        var companyRaw = clean.path("company").path("raw");
        if (!companyRaw.isString()
                || companyRaw.asString().isBlank()
                || companyRaw.asString().length() > 4096) invalid();
        var titleRaw = clean.path("title").path("raw");
        if (!titleRaw.isMissingNode()
                && !titleRaw.isNull()
                && (!titleRaw.isString() || titleRaw.asString().length() > 4096)) invalid();
        boolean titleFound = titleRaw.isString() && !titleRaw.asString().isBlank();
        ObjectNode company = (ObjectNode) clean.path("company");
        company.put("normalized", company.path("raw").asString().strip().toLowerCase(Locale.ROOT));
        company.put("state", "PROPOSED");
        validateCodes(company.path("industryCodes"));
        validateCodes(clean.path("title").path("functionCodes"));
        ObjectNode title = (ObjectNode) clean.path("title");
        if (!titleFound) title.putNull("raw");
        title.put("state", titleFound ? "PROPOSED" : "NOT_FOUND");
        title.put(
                "functionState",
                title.path("functionCodes").isArray() && !title.path("functionCodes").isEmpty()
                        ? "PROPOSED"
                        : "NOT_FOUND");
        company.put(
                "industryState",
                company.path("industryCodes").isArray() && !company.path("industryCodes").isEmpty()
                        ? "PROPOSED"
                        : "NOT_FOUND");
        if (!clean.path("title").path("seniorityCode").isMissingNode()
                && !clean.path("title").path("seniorityCode").isNull()
                && (!clean.path("title").path("seniorityCode").isString()
                        || !clean.path("title")
                                .path("seniorityCode")
                                .asString()
                                .matches("[A-Z][A-Z0-9_]{0,63}"))) invalid();
        var period = (ObjectNode) clean.path("period");
        try {
            validateDate(period.path("start"));
            validateDate(period.path("end"));
            LocalDate start = date(period.path("start")), end = latestDate(period.path("end"));
            if (start != null && end != null && start.isAfter(end)) invalid();
        } catch (DateTimeException ex) {
            invalid();
        }
        if (!period.path("ongoingAsStated").isBoolean()) invalid();
        if (period.path("ongoingAsStated").asBoolean()
                && !period.path("end").isNull()
                && !period.path("end").isMissingNode()) invalid();
        period.put("state", period.path("start").isObject() ? "PROPOSED" : "NOT_FOUND");
        if (!clean.path("descriptionRaw").isMissingNode()
                && (!clean.path("descriptionRaw").isString()
                        || clean.path("descriptionRaw").asString().length() > 16384)) invalid();
        company.remove(List.of("industryBasis", "evidence"));
        ((ObjectNode) clean.path("title")).remove(List.of("canonical", "alternatives", "evidence"));
        period.remove(List.of("alternatives", "evidence"));
        clean.remove(List.of("localId", "issues"));
        return clean;
    }

    private void validateDate(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return;
        if (!node.isObject()
                || !node.path("year").isIntegralNumber()
                || !node.path("year").canConvertToInt()
                || node.path("year").asInt() < 1
                || node.path("year").asInt() > 9999) invalid();
        String p = node.path("precision").asString();
        if (!List.of("YEAR", "MONTH", "DAY").contains(p)) invalid();
        boolean month = !node.path("month").isNull() && !node.path("month").isMissingNode(),
                day = !node.path("day").isNull() && !node.path("day").isMissingNode();
        if (month
                        && (!node.path("month").isIntegralNumber()
                                || !node.path("month").canConvertToInt())
                || day
                        && (!node.path("day").isIntegralNumber()
                                || !node.path("day").canConvertToInt())
                || month != !p.equals("YEAR")
                || day != p.equals("DAY")) invalid();
        date(node);
    }

    private static LocalDate latestDate(JsonNode node) {
        LocalDate first = date(node);
        if (first == null) return null;
        return switch (node.path("precision").asString()) {
            case "YEAR" -> LocalDate.of(first.getYear(), 12, 31);
            case "MONTH" -> YearMonth.from(first).atEndOfMonth();
            default -> first;
        };
    }

    private void validateCodes(JsonNode codes) {
        if (codes.isMissingNode()) return;
        if (!codes.isArray() || codes.size() > 16) invalid();
        for (var code : codes)
            if (!code.isString() || !code.asString().matches("[A-Z][A-Z0-9_]{0,63}")) invalid();
    }

    private static void stripEvidence(JsonNode node) {
        if (node.isObject()) {
            var object = (ObjectNode) node;
            object.remove(List.of("evidence", "descriptionEvidence"));
            for (String field : new ArrayList<>(object.propertyNames()))
                stripEvidence(object.get(field));
        } else if (node.isArray()) for (var child : node) stripEvidence(child);
    }

    private static void invalid() {
        throw new CandidateFailure(400, "VALIDATION_ERROR");
    }
}
