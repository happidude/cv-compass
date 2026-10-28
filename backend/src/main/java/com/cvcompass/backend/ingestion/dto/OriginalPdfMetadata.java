package com.cvcompass.backend.ingestion.dto;

/** Internal storage descriptor. Never return this from a user endpoint. */
public record OriginalPdfMetadata(String key, long size, String mime) {}
