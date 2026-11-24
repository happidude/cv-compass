package com.cvcompass.backend.catalog.service;

import com.cvcompass.backend.candidate.events.CandidateChanged;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NormalizationCandidateListener {
    private final NormalizationLinkService links;

    public NormalizationCandidateListener(NormalizationLinkService links) {
        this.links = links;
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(CandidateChanged event) {
        // Disabling workers stops HTTP/backfill only; alias linking and DB-only queueing still run.
        links.linkCandidate(event.candidateId());
    }
}
