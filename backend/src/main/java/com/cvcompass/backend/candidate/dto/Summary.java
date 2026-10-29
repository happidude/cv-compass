package com.cvcompass.backend.candidate.dto;

public record Summary(
        long id, long version, String fullName, String basicState, String importedAt) {}
