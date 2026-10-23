package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.OcrJobProperties;
import com.cvcompass.backend.ingestion.dto.*;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.exception.OcrStateException;
import com.cvcompass.backend.ingestion.repository.OcrStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Service regression with scripted DB time; never reads or changes host/DB clocks. */
class OcrJobHeartbeatTests {
    @Test void inputAuthorizationDoesNotRenewAndStillUsesLockedDbClock() {
        when(state.wallClock()).thenReturn(BASE.plusSeconds(100));
        assertThat(service.authorizeInput(key).attemptId()).isEqualTo(PUBLIC_ID);
        verify(state,never()).renewed(anyLong(),any());
        var order=inOrder(state); order.verify(state).findAttempt(PUBLIC_ID);
        order.verify(state).lockJob(1); order.verify(state).lockAttempt(3); order.verify(state).wallClock();
    }
    @Test void expiredInputCannotBeDownloaded() {
        when(state.wallClock()).thenReturn(attempt.leaseExpiresAt());
        assertThatThrownBy(()->service.authorizeInput(key)).isInstanceOf(OcrStateException.class);
        verify(state,never()).renewed(anyLong(),any());
    }
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PUBLIC_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String HASH = "a".repeat(64);
    private final OcrStateRepository state = mock(OcrStateRepository.class);
    private final OcrJobProperties properties = new OcrJobProperties();
    private final OcrJobService service = new OcrJobService(state,properties,mock(OcrResultValidator.class));
    private final OcrAttemptKey key = new OcrAttemptKey(PUBLIC_ID,"synthetic_worker",HASH);
    private OcrAttemptState attempt = attempt(BASE.plusSeconds(120),OcrAttemptStatus.ACTIVE);

    @BeforeEach void lockedState() {
        properties.setLeaseDuration(Duration.ofSeconds(37)); // Proves renewal uses configuration, not default 120s.
        when(state.findAttempt(PUBLIC_ID)).thenAnswer(invocation -> Optional.of(attempt));
        when(state.lockJob(1)).thenReturn(Optional.of(new OcrJobState(1,2,"synthetic-v1",1,3,1,BASE,3L,JobStatus.PROCESSING)));
        when(state.lockAttempt(3)).thenAnswer(invocation -> attempt);
        doAnswer(invocation -> { attempt=attempt(invocation.getArgument(1),OcrAttemptStatus.ACTIVE); return null; })
                .when(state).renewed(eq(3L),any(Instant.class));
    }
    private OcrAttemptState attempt(Instant expiry,OcrAttemptStatus status) {
        return new OcrAttemptState(3,PUBLIC_ID,1,1,"synthetic_worker",PUBLIC_ID,HASH,HASH,
                expiry,BASE.plusSeconds(150),BASE.plusSeconds(210),status,null);
    }
    private void stale() {
        assertThatThrownBy(() -> service.heartbeat(key)).isInstanceOfSatisfying(OcrStateException.class,
                error -> assertThat(error.reason()).isEqualTo(OcrStateError.STALE_ATTEMPT));
        verify(state,never()).renewed(anyLong(),any());
    }
    @Test void backwardDbClockCannotShortenPersistedOrReturnedLeaseAndDeadlineStillCaps() {
        when(state.wallClock()).thenReturn(BASE.plusSeconds(100),BASE.plusSeconds(90),BASE.plusSeconds(135));
        assertThat(service.heartbeat(key).leaseExpiresAt()).isEqualTo(BASE.plusSeconds(137));
        var backward=service.heartbeat(key);
        assertThat(backward.serverTime()).isEqualTo(BASE.plusSeconds(90));
        assertThat(backward.leaseExpiresAt()).isEqualTo(BASE.plusSeconds(137));
        assertThat(attempt.leaseExpiresAt()).isEqualTo(BASE.plusSeconds(137));
        assertThat(service.heartbeat(key).leaseExpiresAt()).isEqualTo(BASE.plusSeconds(150));
        assertThat(attempt.leaseExpiresAt()).isEqualTo(attempt.hardDeadlineAt());
        verify(state,times(2)).renewed(3,BASE.plusSeconds(137));
        verify(state).renewed(3,BASE.plusSeconds(150));
    }
    @Test void expiryEqualityCannotBeRenewed() {
        when(state.wallClock()).thenReturn(attempt.leaseExpiresAt()); stale();
    }
    @Test void afterExpiryCannotBeRenewed() {
        when(state.wallClock()).thenReturn(attempt.leaseExpiresAt().plusSeconds(1)); stale();
    }
    @Test void expiredStatusCannotBeResurrectedByClockRollback() {
        attempt=attempt(BASE.plusSeconds(120),OcrAttemptStatus.EXPIRED);
        when(state.wallClock()).thenReturn(BASE.plusSeconds(50)); stale();
    }
    @Test void hardDeadlineEqualityCannotBeRenewed() {
        attempt=attempt(BASE.plusSeconds(150),OcrAttemptStatus.ACTIVE);
        when(state.wallClock()).thenReturn(attempt.hardDeadlineAt()); stale();
    }
    @Test void renewalReadsDbClockOnlyAfterJobThenAttemptLocks() {
        when(state.wallClock()).thenReturn(BASE.plusSeconds(100)); service.heartbeat(key);
        var order=inOrder(state);
        order.verify(state).findAttempt(PUBLIC_ID);
        order.verify(state).lockJob(1);
        order.verify(state).lockAttempt(3);
        order.verify(state).wallClock();
        order.verify(state).renewed(3,BASE.plusSeconds(137));
    }
}
