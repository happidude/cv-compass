package com.cvcompass.backend.candidate.dto;

import tools.jackson.databind.JsonNode;

public record History(
        long id,
        long version,
        String actor,
        String action,
        JsonNode before,
        JsonNode after,
        String at) {}
