package com.cvcompass.backend.ingestion.dto;

/** An operator policy decision, never supplied by an upload caller. */
public record OcrAdmission(String mode, String profileRevision, String fixtureId) {}
