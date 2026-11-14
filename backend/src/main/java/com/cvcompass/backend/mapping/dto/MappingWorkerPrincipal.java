package com.cvcompass.backend.mapping.dto;

public record MappingWorkerPrincipal(String workerId) {
    public static final String ATTRIBUTE = MappingWorkerPrincipal.class.getName();
}
