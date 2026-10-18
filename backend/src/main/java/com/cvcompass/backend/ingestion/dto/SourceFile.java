package com.cvcompass.backend.ingestion.dto;

public record SourceFile(String name, long size, String modified, String fileKey) { }
