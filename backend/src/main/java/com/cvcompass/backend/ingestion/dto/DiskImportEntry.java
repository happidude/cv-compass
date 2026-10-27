package com.cvcompass.backend.ingestion.dto;

public record DiskImportEntry(
        long id,
        SourceFile file,
        String sha256,
        String state,
        int attempts,
        int moveAttempts,
        Long documentId,
        String target) {}
