package com.cvcompass.backend.search.dto;

import java.math.BigDecimal;
import java.util.List;

public record CandidateSearchPage(List<Result> content, String nextCursor, boolean hasNext) {
    public record Result(
            long id,
            String fullName,
            Title currentTitle,
            Company currentCompany,
            String province,
            Integer totalExperienceMonths,
            boolean experienceEstimated,
            BigDecimal score,
            List<String> matchReasons,
            MatchedCompany matchedCompany) {}

    public record Title(long id, String name, boolean aiNormalized) {}

    public record Company(long id, String name, String parentName, boolean aiNormalized) {}

    public record MatchedCompany(long id, String name) {}

    public record CompanySuggestion(long id, String name, String parentName) {}

    public record TaxonomyOption(String code, String label) {}
}
