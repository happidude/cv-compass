package com.cvcompass.backend.candidate.dto;

public record Summary(
        long id,
        long version,
        String fullName,
        String basicState,
        String importedAt,
        String sourceFilename) {
    public Summary(long id, long version, String fullName, String basicState, String importedAt) {
        this(id, version, fullName, basicState, importedAt, null);
    }

    public Summary withSourceFilename(String filename) {
        return new Summary(id, version, fullName, basicState, importedAt, filename);
    }
}
