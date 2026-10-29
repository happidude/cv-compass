package com.cvcompass.backend.candidate.dto;

import tools.jackson.databind.JsonNode;

public record ProfileCore(
        long version, JsonNode basic, String basicState, String basicOrigin, JsonNode extensions) {}
