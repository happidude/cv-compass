package com.cvcompass.backend.search.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.cvcompass.backend.candidate.events.CandidateChanged;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchCandidateListenerTests {
    private final SearchIndexer indexer = mock(SearchIndexer.class);
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Savepoint savepoint = mock(Savepoint.class);
    private final SearchCandidateListener listener = new SearchCandidateListener(indexer, source);

    @BeforeEach
    void connection() throws SQLException {
        when(source.getConnection()).thenReturn(connection);
        when(connection.setSavepoint()).thenReturn(savepoint);
    }

    @Test
    void successfulIndexReleasesItsSavepoint() throws SQLException {
        listener.changed(new CandidateChanged(7));
        var order = inOrder(connection, indexer);
        order.verify(connection).setSavepoint();
        order.verify(indexer).rebuildInCurrentTransaction(7);
        order.verify(connection).releaseSavepoint(savepoint);
        verify(connection, never()).rollback(savepoint);
    }

    @Test
    void failedIndexRollsBackBeforeReleasingItsSavepoint() throws SQLException {
        doThrow(new IllegalStateException("synthetic failure"))
                .when(indexer)
                .rebuildInCurrentTransaction(7);
        listener.changed(new CandidateChanged(7));
        var order = inOrder(connection, indexer);
        order.verify(connection).setSavepoint();
        order.verify(indexer).rebuildInCurrentTransaction(7);
        order.verify(connection).rollback(savepoint);
        order.verify(connection).releaseSavepoint(savepoint);
    }

    @Test
    void savepointCreationFailurePropagatesWithoutAttemptingIndexing() throws SQLException {
        var failure = new SQLException("synthetic savepoint failure");
        when(connection.setSavepoint()).thenThrow(failure);
        assertThatThrownBy(() -> listener.changed(new CandidateChanged(7))).isSameAs(failure);
        verifyNoInteractions(indexer);
    }

    @Test
    void rollbackFailurePropagatesBecauseTheOuterTransactionMayBeAborted() throws SQLException {
        doThrow(new IllegalStateException("synthetic failure"))
                .when(indexer)
                .rebuildInCurrentTransaction(7);
        var failure = new SQLException("synthetic rollback failure");
        doThrow(failure).when(connection).rollback(savepoint);
        assertThatThrownBy(() -> listener.changed(new CandidateChanged(7))).isSameAs(failure);
        verify(connection, never()).releaseSavepoint(savepoint);
    }
}
