package com.cvcompass.backend.search.dto;

import com.cvcompass.backend.search.exception.SearchFailure;
import java.util.List;
import org.springframework.util.MultiValueMap;

public record CandidateSearchRequest(
        String q,
        String company,
        Long companyId,
        String title,
        List<String> function,
        List<String> seniority,
        List<String> scope,
        List<String> industry,
        List<String> province,
        String minYears,
        String maxYears,
        String cursor,
        String limit) {
    public static CandidateSearchRequest from(MultiValueMap<String, String> parameters) {
        return new CandidateSearchRequest(
                parameters.getFirst("q"),
                parameters.getFirst("company"),
                companyId(parameters),
                parameters.getFirst("title"),
                parameters.getOrDefault("function", List.of()),
                parameters.getOrDefault("seniority", List.of()),
                parameters.getOrDefault("scope", List.of()),
                parameters.getOrDefault("industry", List.of()),
                parameters.getOrDefault("province", List.of()),
                parameters.getFirst("minYears"),
                parameters.getFirst("maxYears"),
                parameters.getFirst("cursor"),
                parameters.getFirst("limit"));
    }

    private static Long companyId(MultiValueMap<String, String> parameters) {
        if (!parameters.containsKey("companyId")) return null;
        if (parameters.containsKey("company"))
            throw new SearchFailure("companyId", "Use only one of company and companyId");
        var values = parameters.get("companyId");
        if (values == null || values.size() != 1)
            throw new SearchFailure("companyId", "Must contain a single value");
        try {
            long id = Long.parseLong(values.getFirst());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException failure) {
            throw new SearchFailure("companyId", "Must be a positive long integer");
        }
    }
}
