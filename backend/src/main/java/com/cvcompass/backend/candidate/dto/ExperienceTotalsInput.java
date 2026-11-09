package com.cvcompass.backend.candidate.dto;

import java.time.LocalDate;
import java.util.UUID;

public record ExperienceTotalsInput(
        UUID id,
        LocalDate startDate,
        String startPrecision,
        LocalDate endDate,
        String endPrecision,
        boolean ongoingAsStated,
        String periodState,
        boolean deleted,
        boolean countInTotal,
        Long documentId) {}
