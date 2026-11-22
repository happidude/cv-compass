package com.cvcompass.backend.catalog.dto;

public record NormalizationWorkerPrincipal(String workerId) {
    public static final String ATTRIBUTE = NormalizationWorkerPrincipal.class.getName();
}
