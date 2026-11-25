package com.cvcompass.backend.search.dto;

import java.util.List;
import org.springframework.util.MultiValueMap;

public record CandidateSearchRequest(
        String q,
        String company,
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
}
