package com.cvcompass.backend.candidate.dto;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record Proposal(UUID id, long documentId, long resultId, JsonNode data, String kind) {
    public Proposal(UUID id, long documentId, long resultId, JsonNode data) {
        this(id, documentId, resultId, data, "EXPERIENCE");
    }
}
