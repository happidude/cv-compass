package com.cvcompass.backend.search.service;

import com.cvcompass.backend.candidate.events.CandidateChanged;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SearchCandidateListener {
    private static final Logger LOG = LoggerFactory.getLogger(SearchCandidateListener.class);
    private final SearchIndexer indexer;
    private final DataSource dataSource;

    public SearchCandidateListener(SearchIndexer indexer, DataSource dataSource) {
        this.indexer = indexer;
        this.dataSource = dataSource;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = SQLException.class)
    public void changed(CandidateChanged event) throws SQLException {
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            // PostgreSQL aborts the transaction after SQL errors without a savepoint.
            // JpaDialect does not support NESTED, so only the JDBC index work is isolated here.
            var savepoint = connection.setSavepoint();
            try {
                indexer.rebuildInCurrentTransaction(event.candidateId());
            } catch (RuntimeException failure) {
                connection.rollback(savepoint);
                LOG.warn(
                        "Search rebuild failed candidateId={} error={}",
                        event.candidateId(),
                        failure.getClass().getSimpleName());
            }
            connection.releaseSavepoint(savepoint);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
