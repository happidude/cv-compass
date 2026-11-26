package com.cvcompass.backend.search.service;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.search.config.SearchProperties;
import com.cvcompass.backend.search.dto.RebuildResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SearchRebuildService {
    private static final Logger LOG = LoggerFactory.getLogger(SearchRebuildService.class);
    private final CatalogLinks candidates;
    private final SearchIndexer indexer;
    private final SearchProperties properties;
    private final TransactionTemplate candidateTransaction;

    public SearchRebuildService(
            CatalogLinks candidates,
            SearchIndexer indexer,
            SearchProperties properties,
            PlatformTransactionManager transactions) {
        this.candidates = candidates;
        this.indexer = indexer;
        this.properties = properties;
        candidateTransaction = new TransactionTemplate(transactions);
        candidateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public RebuildResult rebuildAll() {
        // Bound the run to its initial population even while new candidates are being published.
        long through = candidates.lastCandidateId();
        long after = 0;
        long rebuilt = 0;
        long failed = 0;
        while (true) {
            var ids = candidates.indexCandidates(after, through, properties.getRebuildBatchSize());
            if (ids.isEmpty()) return new RebuildResult(rebuilt, failed);
            for (long id : ids) {
                try {
                    candidateTransaction.executeWithoutResult(status -> indexer.rebuild(id));
                    rebuilt++;
                } catch (RuntimeException e) {
                    // One bad candidate must not block the rest; log only the id and class.
                    failed++;
                    LOG.warn(
                            "Search rebuild failed candidateId={} error={}",
                            id,
                            e.getClass().getSimpleName());
                }
            }
            after = ids.getLast();
        }
    }
}
