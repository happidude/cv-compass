package com.cvcompass.backend.candidate.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ExperienceEdit(
        @NotNull @PositiveOrZero Long version,
        UUID experienceId,
        JsonNode data,
        @NotNull @Pattern(regexp = "SAVE|CONFIRM|DELETE") String operation) {}
