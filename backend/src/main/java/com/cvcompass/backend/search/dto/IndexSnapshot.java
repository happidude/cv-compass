package com.cvcompass.backend.search.dto;

import java.util.List;

public record IndexSnapshot(
        long candidateId,
        String text,
        List<Long> companyIds,
        List<Long> parentCompanyIds,
        List<Long> titleIds,
        List<String> functionCodes,
        List<String> seniorityCodes,
        List<String> industryCodes,
        List<String> scopeCodes,
        String province,
        int months,
        boolean aiNormalization) {}
