package com.cvcompass.backend.candidate.dto;

import java.util.List;

public record Page(List<Summary> content, boolean hasNext, Long nextBeforeId) {}
