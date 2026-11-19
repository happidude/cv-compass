package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.candidate.repository.PublicationRepository;
import com.cvcompass.backend.candidate.service.CandidateExperienceService;
import com.cvcompass.backend.candidate.service.CandidateQueryService;
import com.cvcompass.backend.candidate.service.CandidateRevisionService;
import com.cvcompass.backend.candidate.service.PublicationService;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import com.cvcompass.backend.mapping.api.PublishableMapping;
import com.cvcompass.backend.mapping.config.MappingProperties;
import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingResult;
import com.cvcompass.backend.mapping.repository.MappingRemoteRepository;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import com.cvcompass.backend.mapping.service.ExperienceEngine;
import com.cvcompass.backend.mapping.service.MappingDigests;
import com.cvcompass.backend.mapping.service.MappingEngine;
import com.cvcompass.backend.mapping.service.MappingRemoteFixtures;
import com.cvcompass.backend.mapping.service.MappingRemoteService;
import com.cvcompass.backend.mapping.service.MappingRemoteVerifier;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.web.OperationalLog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OperationalLogLifecycleTests {
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.cvcompass.events");
    private final ListAppender<ILoggingEvent> appender =
            new ListAppender<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };

    @BeforeEach
    void captureCommittedLogs() {
        appender.start();
        logger.addAppender(appender);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeCapture() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        logger.detachAppender(appender);
        appender.stop();
    }

    private Map<String, String> commitOne() {
        assertThat(appender.list).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        assertThat(appender.list).hasSize(1);
        var context = appender.list.getFirst().getMDCPropertyMap();
        assertThat(context.keySet())
                .isSubsetOf(
                        "event",
                        "errorCode",
                        "documentId",
                        "jobId",
                        "attemptId",
                        "extractionId",
                        "status",
                        "correlationId");
        assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain("PRIVATE");
        return context;
    }

    @Test
    void enqueueLogsOnlyANewInsertWithDocumentAndJobIdentity() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyLong()))
                .thenReturn(
                        MappingRemoteFixtures.JSON.writeValueAsString(
                                MappingRemoteFixtures.definition()));
        when(jdbc.update(anyString(), anyLong(), anyLong(), anyString(), eq(3))).thenReturn(1, 0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyLong(), anyLong(), anyString()))
                .thenReturn(20L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(20L))).thenReturn(12L);
        var repository = new MappingRepository(jdbc, MappingRemoteFixtures.JSON);
        repository.enqueue(11, 13, MappingRemoteFixtures.definition().engineCompatibility(), 3);
        repository.enqueue(11, 13, MappingRemoteFixtures.definition().engineCompatibility(), 3);
        assertThat(commitOne())
                .containsEntry("event", "MAPPING_ENQUEUED")
                .containsEntry("documentId", "12")
                .containsEntry("jobId", "20")
                .containsEntry("extractionId", "11");
    }

    @Test
    void remoteClaimLogResolvesDocumentFromJobWithoutLoggingBody() {
        var attempts = mock(MappingRemoteRepository.class);
        var jobs = mock(MappingRepository.class);
        var inputs = mock(OcrExtractionReader.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(attempts.ready()).thenReturn(Optional.of(1L));
        when(jobs.readClaim(1)).thenReturn(MappingRemoteFixtures.claim());
        when(jobs.documentId(1)).thenReturn(12L);
        when(inputs.find(11)).thenReturn(Optional.of(MappingRemoteFixtures.input()));
        when(jobs.pinInput(eq(1L), anyString())).thenReturn(true);
        UUID id = UUID.fromString("a0000000-0000-4000-8000-000000000001");
        var attempt =
                new MappingRemoteRepository.Attempt(
                        id,
                        1,
                        1,
                        "synthetic",
                        "a".repeat(64),
                        "b".repeat(64),
                        Instant.MAX,
                        Instant.MAX,
                        Instant.MAX,
                        "ACTIVE",
                        null,
                        MappingRemoteFixtures.provenance().toString(),
                        "c".repeat(64),
                        null);
        when(attempts.lock(any())).thenReturn(attempt);
        when(jobs.lockJob(1))
                .thenReturn(new MappingRepository.Job("PROCESSING", null, null, 1, null, 3));
        when(jobs.now()).thenReturn(Instant.EPOCH);
        var properties = new MappingRemoteProperties();
        properties.setEnabled(true);
        var service =
                new MappingRemoteService(
                        attempts,
                        jobs,
                        inputs,
                        properties,
                        new MappingProperties(),
                        MappingRemoteFixtures.JSON,
                        new MappingRemoteVerifier(),
                        transactions);
        assertThat(
                        service.claim(
                                        "synthetic",
                                        UUID.randomUUID(),
                                        "b".repeat(64),
                                        MappingRemoteFixtures.claimBody().toString())
                                .status())
                .isEqualTo(201);
        assertThat(commitOne())
                .containsEntry("event", "MAPPING_REMOTE_CLAIMED")
                .containsEntry("documentId", "12")
                .containsEntry("jobId", "1")
                .containsEntry("attemptId", id.toString());
    }

    @Test
    void publicationRecordsSkippedReasonAndIdentityAfterCommit() {
        var mappings = mock(CompletedMappings.class);
        var documents = mock(CandidateDocuments.class);
        var result = new CompletedMappings.Result(30, 99, "NEEDS_REVIEW", "PRIVATE", 11, 13, 12);
        when(mappings.read(30)).thenReturn(Optional.of(result));
        when(mappings.jobId(30)).thenReturn(20L);
        when(documents.valid(12)).thenReturn(true);
        var service =
                new PublicationService(
                        mock(PublicationRepository.class),
                        mock(CandidateProfileRepository.class),
                        mappings,
                        documents,
                        mock(CandidateQueryService.class),
                        mock(CandidateRevisionService.class),
                        mock(CandidateExperienceService.class),
                        MappingRemoteFixtures.JSON);
        assertThat(service.publish(30)).isTrue();
        assertThat(commitOne())
                .containsEntry("event", "CANDIDATE_PUBLICATION_RECORDED")
                .containsEntry("documentId", "12")
                .containsEntry("jobId", "20")
                .containsEntry("status", "SKIPPED")
                .containsEntry("errorCode", "UNSUPPORTED_MAPPING_VERSION");
    }

    @Test
    void mappingClaimLogContainsDocumentIdentityAndOnlyAllowedKeys() {
        mappingLifecycleLogContainsDocumentIdentity(false);
    }

    @Test
    void mappingCompletionLogContainsDocumentIdentityAndOnlyAllowedKeys() {
        mappingLifecycleLogContainsDocumentIdentity(true);
    }

    private void mappingLifecycleLogContainsDocumentIdentity(boolean complete) {
        var jobs = mock(MappingRepository.class);
        var inputs = mock(OcrExtractionReader.class);
        var engine = mock(MappingEngine.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var claim =
                new MappingClaim(
                        1,
                        11,
                        13,
                        "synthetic",
                        MappingEngine.VERSION,
                        1,
                        UUID.fromString("a0000000-0000-4000-8000-000000000001"),
                        Instant.MAX,
                        null);
        when(jobs.documentId(1)).thenReturn(12L);
        var service =
                new MappingService(
                        jobs,
                        inputs,
                        engine,
                        MappingRemoteFixtures.JSON,
                        transactions,
                        new MappingProperties(),
                        mock(ExperienceEngine.class));
        if (complete) {
            var input = MappingRemoteFixtures.input();
            String hash = MappingDigests.sha256(MappingDigests.canonical(input));
            var result =
                    new MappingResult(
                            1,
                            11,
                            input.schemaVersion(),
                            13,
                            "synthetic",
                            MappingEngine.VERSION,
                            "NEEDS_REVIEW",
                            false,
                            null,
                            List.of(),
                            hash,
                            12,
                            input.profileRevision());
            when(jobs.readClaim(1)).thenReturn(claim);
            when(inputs.find(11)).thenReturn(Optional.of(input));
            when(jobs.pinInput(1, hash)).thenReturn(true);
            var processing =
                    new MappingRepository.Job(
                            "PROCESSING", claim.token(), Instant.MAX, 1, Instant.MAX, 3);
            var completed =
                    new MappingRepository.Job(
                            "COMPLETED", claim.token(), Instant.MAX, 1, Instant.MAX, 3);
            when(jobs.lockJob(1)).thenReturn(processing, processing, completed);
            when(jobs.now()).thenReturn(Instant.EPOCH);
            when(engine.map(claim, input)).thenReturn(result);
            service.complete(claim, result);
        } else {
            when(jobs.claim(
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            any(),
                            any()))
                    .thenReturn(Optional.of(claim));
            assertThat(service.claim()).contains(claim);
        }
        assertThat(commitOne())
                .containsEntry("event", complete ? "MAPPING_COMPLETED" : "MAPPING_CLAIMED")
                .containsEntry("documentId", "12")
                .containsEntry("jobId", "1")
                .containsEntry("extractionId", "11");
    }

    @Test
    void remoteAcceptedLogContainsDocumentIdentityAndOnlyAllowedKeys() {
        remoteOutcomeLogContainsDocumentIdentity(true);
    }

    @Test
    void remoteFailedLogContainsDocumentIdentityAndOnlyAllowedKeys() {
        remoteOutcomeLogContainsDocumentIdentity(false);
    }

    private void remoteOutcomeLogContainsDocumentIdentity(boolean accepted) {
        var attempts = mock(MappingRemoteRepository.class);
        var jobs = mock(MappingRepository.class);
        var inputs = mock(OcrExtractionReader.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID id = UUID.fromString("a0000000-0000-4000-8000-000000000001");
        var input = MappingRemoteFixtures.input();
        String hash = MappingDigests.sha256(MappingDigests.canonical(input));
        String tokenHash = "b".repeat(64);
        var attempt =
                new MappingRemoteRepository.Attempt(
                        id,
                        1,
                        1,
                        "synthetic",
                        "a".repeat(64),
                        tokenHash,
                        Instant.MAX,
                        Instant.MAX,
                        Instant.MAX,
                        "ACTIVE",
                        null,
                        MappingRemoteFixtures.provenance().toString(),
                        hash,
                        null);
        when(attempts.jobId(id)).thenReturn(Optional.of(1L));
        when(attempts.lock(id)).thenReturn(attempt);
        when(jobs.lockJob(1))
                .thenReturn(new MappingRepository.Job("PROCESSING", null, null, 1, null, 3));
        when(jobs.documentId(1)).thenReturn(12L);
        when(jobs.readClaim(1)).thenReturn(MappingRemoteFixtures.claim());
        when(jobs.now()).thenReturn(Instant.EPOCH);
        when(inputs.find(11)).thenReturn(Optional.of(input));
        var properties = new MappingRemoteProperties();
        properties.setEnabled(true);
        var service =
                new MappingRemoteService(
                        attempts,
                        jobs,
                        inputs,
                        properties,
                        new MappingProperties(),
                        MappingRemoteFixtures.JSON,
                        new MappingRemoteVerifier(),
                        transactions);
        var reply =
                accepted
                        ? service.result(
                                "synthetic",
                                id,
                                tokenHash,
                                MappingRemoteFixtures.result(hash).toString())
                        : service.failure(
                                "synthetic", id, tokenHash, "{\"code\":\"PROVIDER_ERROR\"}");
        assertThat(reply).containsEntry("status", accepted ? "ACCEPTED" : "FAILED");
        assertThat(commitOne())
                .containsEntry(
                        "event", accepted ? "MAPPING_REMOTE_ACCEPTED" : "MAPPING_REMOTE_FAILED")
                .containsEntry("documentId", "12")
                .containsEntry("jobId", "1")
                .containsEntry("attemptId", id.toString())
                .containsEntry("errorCode", accepted ? "NONE" : "PROVIDER_ERROR");
    }

    @Test
    void publicationRecordsPublishedStatusAfterCommit() {
        publicationRecordsStatusAfterCommit(true);
    }

    @Test
    void publicationRecordsProposedStatusAfterCommit() {
        publicationRecordsStatusAfterCommit(false);
    }

    private void publicationRecordsStatusAfterCommit(boolean initial) {
        var mappings = mock(CompletedMappings.class);
        var documents = mock(CandidateDocuments.class);
        var result =
                new CompletedMappings.Result(
                        30,
                        2,
                        "NEEDS_REVIEW",
                        "{\"documentId\":12,\"extractionId\":11,\"experiences\":[{}]}",
                        11,
                        13,
                        12);
        when(mappings.read(30)).thenReturn(Optional.of(result));
        when(mappings.jobId(30)).thenReturn(20L);
        when(mappings.publishable(result))
                .thenReturn(Optional.of(new PublishableMapping(2, "synthetic", false, false)));
        when(documents.valid(12)).thenReturn(true);
        when(documents.lockAndResolve(12)).thenReturn(55L);
        var profiles = mock(CandidateProfileRepository.class);
        when(profiles.experienceCount(55)).thenReturn(initial ? 0 : 1);
        var service =
                new PublicationService(
                        mock(PublicationRepository.class),
                        profiles,
                        mappings,
                        documents,
                        mock(CandidateQueryService.class, RETURNS_DEEP_STUBS),
                        mock(CandidateRevisionService.class),
                        mock(CandidateExperienceService.class),
                        MappingRemoteFixtures.JSON);
        assertThat(service.publish(30)).isTrue();
        assertThat(commitOne())
                .containsEntry("documentId", "12")
                .containsEntry("jobId", "20")
                .containsEntry("status", initial ? "PUBLISHED" : "PROPOSED")
                .containsEntry("errorCode", "NONE");
    }

    @Test
    void newEventsKeepTheExistingContextAllowlist() {
        OperationalLog.committed(
                Level.INFO,
                OperationalLog.Event.MAPPING_ENQUEUED,
                null,
                Map.of("documentId", 12, "rawText", "PRIVATE", "prompt_tokens", 42));
        assertThat(commitOne()).doesNotContainKeys("rawText", "prompt_tokens");
    }
}
