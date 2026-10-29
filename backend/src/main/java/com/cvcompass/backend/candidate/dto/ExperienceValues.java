package com.cvcompass.backend.candidate.dto;

import java.time.LocalDate;

public record ExperienceValues(
        String companyRaw,
        String companyNormalized,
        String titleRaw,
        LocalDate startDate,
        String startPrecision,
        LocalDate endDate,
        String endPrecision,
        boolean ongoingAsStated,
        String functionCodes,
        String industryCodes,
        String seniorityCode,
        String descriptionRaw) {}
