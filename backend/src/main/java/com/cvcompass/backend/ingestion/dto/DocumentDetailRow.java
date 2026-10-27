package com.cvcompass.backend.ingestion.dto;

/** Internal read projection, never returned directly by a controller. */
public record DocumentDetailRow(
        DocumentOverview overview, Long extractionId, String resultJson, String legacyRawText) {}
