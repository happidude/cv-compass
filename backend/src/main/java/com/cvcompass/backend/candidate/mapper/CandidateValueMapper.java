package com.cvcompass.backend.candidate.mapper;

import com.cvcompass.backend.candidate.dto.BasicValues;
import com.cvcompass.backend.candidate.dto.ExperienceValues;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class CandidateValueMapper {

    private String array(JsonNode node, String field) {
        return node.path(field).isArray() ? node.path(field).toString() : "[]";
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }

    public static LocalDate date(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return null;
        return LocalDate.of(
                node.path("year").asInt(), node.path("month").asInt(1), node.path("day").asInt(1));
    }

    public BasicValues basic(JsonNode value) {
        return new BasicValues(
                text(value, "fullName"),
                text(value, "email"),
                text(value, "phone"),
                text(value, "location"),
                text(value, "summary"));
    }

    public ExperienceValues experience(JsonNode data) {
        var company = data.path("company");
        var title = data.path("title");
        var period = data.path("period");
        boolean proposed = "PROPOSED".equals(period.path("state").asString());
        var start = period.path("start");
        var end = period.path("end");
        boolean hasStart = proposed && start.isObject();
        boolean hasEnd = proposed && end.isObject();
        return new ExperienceValues(
                text(company, "raw"),
                text(company, "normalized"),
                text(title, "raw"),
                hasStart ? date(start) : null,
                hasStart ? text(start, "precision") : null,
                hasEnd ? date(end) : null,
                hasEnd ? text(end, "precision") : null,
                period.path("ongoingAsStated").asBoolean(),
                array(title, "functionCodes"),
                array(company, "industryCodes"),
                text(title, "seniorityCode"),
                text(data, "descriptionRaw"));
    }
}
