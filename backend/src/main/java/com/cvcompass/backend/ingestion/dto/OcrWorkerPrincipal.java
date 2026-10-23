package com.cvcompass.backend.ingestion.dto;

import java.util.Set;

public record OcrWorkerPrincipal(String workerId, Set<String> profiles) {
    public static final String ATTRIBUTE = OcrWorkerPrincipal.class.getName();
    public OcrWorkerPrincipal { profiles = Set.copyOf(profiles); }
}
