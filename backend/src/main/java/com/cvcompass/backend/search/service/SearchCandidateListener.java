package com.cvcompass.backend.search.service;

import com.cvcompass.backend.candidate.events.CandidateChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SearchCandidateListener {
    private static final Logger LOG = LoggerFactory.getLogger(SearchCandidateListener.class);
    private final SearchIndexer indexer;

    public SearchCandidateListener(SearchIndexer indexer) {
        this.indexer = indexer;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(CandidateChanged event) {
        try {
            indexer.rebuild(event.candidateId());
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Search rebuild failed candidateId={} error={}",
                    event.candidateId(),
                    failure.getClass().getSimpleName());
        }
    }
}
