package com.cvcompass.backend.mapping.dto;

import java.time.LocalDate;

public record RemapRequest(
        String scope, Long batchId, LocalDate from, LocalDate to, String issueCode) {}
