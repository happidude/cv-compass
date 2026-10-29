package com.cvcompass.backend.candidate.dto;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record Experience(
        UUID id, String state, String origin, Long documentId, Long resultId, JsonNode data) {}
