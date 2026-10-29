package com.cvcompass.backend.candidate.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record Decision(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Pattern(regexp = "ACCEPT|EDIT|REJECT") String decision,
        UUID targetExperienceId,
        JsonNode data) {}
