package com.cvcompass.backend.candidate.metrics;

/** HR-visible experience metrics, using document import dates as reference dates. */
public interface ExperienceMetrics {
    long totalMonths(long candidateId);
}
