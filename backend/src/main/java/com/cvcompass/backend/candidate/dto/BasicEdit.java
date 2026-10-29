package com.cvcompass.backend.candidate.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.JsonNode;

public record BasicEdit(@NotNull @PositiveOrZero Long version, JsonNode data, boolean confirm) {}
