package com.cvcompass.backend.candidate.events;

/** Published synchronously while the profile writer holds its transaction and row lock. */
public record CandidateChanged(long candidateId) {}
