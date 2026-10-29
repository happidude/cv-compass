package com.cvcompass.backend.candidate.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record DocumentLink(@NotNull @PositiveOrZero Long version, @Positive long documentId) {}
