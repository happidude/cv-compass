package com.cvcompass.backend.ingestion.dto;

/** Distinguishes a missing document row from an unlinked document. */
public record DocumentCandidate(Long candidateId) {}
