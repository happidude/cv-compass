package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.mapping.config.MappingProperties;
import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.repository.MappingRemoteRepository;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MappingRemoteServiceTests {
    @Test
    void missingInputCommitsRetryConsumptionWithoutCreatingAnActiveAttempt() {
        var attempts = mock(MappingRemoteRepository.class);
        var jobs = mock(MappingRepository.class);
        var inputs = mock(OcrExtractionReader.class);
        var transactions = mock(PlatformTransactionManager.class);
        var transaction = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        when(attempts.ready()).thenReturn(Optional.of(1L));
        when(jobs.readClaim(1)).thenReturn(MappingRemoteFixtures.claim());
        when(inputs.find(11)).thenReturn(Optional.empty());
        when(jobs.lockJob(1))
                .thenReturn(new MappingRepository.Job("PENDING", null, null, 2, null, 3));
        var remote = new MappingRemoteProperties();
        remote.setEnabled(true);
        var mapping = new MappingProperties();
        var service =
                new MappingRemoteService(
                        attempts,
                        jobs,
                        inputs,
                        remote,
                        mapping,
                        MappingRemoteFixtures.JSON,
                        new MappingRemoteVerifier(),
                        transactions);

        assertThat(
                        service.claim(
                                        "synthetic_worker",
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        MappingRemoteFixtures.claimBody().toString())
                                .status())
                .isEqualTo(204);

        verify(attempts).consumeInputFailure(1);
        verify(jobs).fail(1, 2, 3, "INPUT_CHANGED", true, mapping.getRetryBackoff());
        verify(attempts, never()).start(anyLong(), any(), any(), any());
        verify(transactions).commit(transaction);
        verify(transactions, never()).rollback(any());
    }
}
