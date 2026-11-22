package com.cvcompass.backend.catalog.config;

import com.cvcompass.backend.catalog.service.NormalizationLinkService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cv.catalog.normalization.enabled", havingValue = "true")
public class NormalizationBackfillJob {
    private final NormalizationLinkService links;
    private final NormalizationProperties properties;

    public NormalizationBackfillJob(
            NormalizationLinkService links, NormalizationProperties properties) {
        this.links = links;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${cv.catalog.normalization.backfill-delay:15m}",
            initialDelayString = "${cv.catalog.normalization.backfill-delay:15m}")
    public void backfill() {
        try {
            long after = 0, through = links.lastCandidateId();
            while (after < through)
                after = links.backfillBatch(after, through, properties.getBatchSize());
        } catch (RuntimeException failure) {
            // The scheduler logs uncaught exceptions; SQL causes can contain raw aliases.
            throw new IllegalStateException("NORMALIZATION_BACKFILL_FAILED");
        }
    }
}
