package com.cvcompass.backend.ingestion.dto;

/** Opaque storage key is internal only and is never serialized by a controller. */
public record OcrInput(String storageKey, long sizeBytes, String sha256) {}
