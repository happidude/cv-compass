package com.cvcompass.backend.candidate.dto;

import java.time.LocalDate;

public record ExperienceReadRow(
        Experience experience,
        LocalDate startDate,
        String startPrecision,
        LocalDate endDate,
        String endPrecision,
        boolean ongoingAsStated) {}
