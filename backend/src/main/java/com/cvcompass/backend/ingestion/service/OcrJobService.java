package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.OcrJobProperties;
import com.cvcompass.backend.ingestion.dto.*;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.exception.OcrStateException;
import com.cvcompass.backend.ingestion.repository.OcrStateRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

/** Trusted internal state-machine seam. No HTTP, authentication, scheduler or enqueue hooks. */
@Service
@ConditionalOnProperty(prefix = "cv.ocr", name = "enabled", havingValue = "true")
public class OcrJobService {
    private final OcrStateRepository state;
    private final OcrJobProperties properties;
    private final OcrResultValidator results;

    public OcrJobService(OcrStateRepository state, OcrJobProperties properties, OcrResultValidator results) {
        this.state = state; this.properties = properties; this.results = results;
    }

    @Transactional
    public Optional<OcrClaim> claim(OcrClaimRequest request) {
        state.lockClaimKey(request.workerId(),request.claimRequestId());
        String digest = OcrResultValidator.digest(OcrProtocol.JOB_VERSION + ":" + request.profileRevision());
        var previous = state.findClaim(request.workerId(),request.claimRequestId());
        if (previous.isPresent()) {
            OcrJobState job = job(previous.get().jobId());
            OcrAttemptState attempt = state.lockAttempt(previous.get().id());
            if (!equal(attempt.leaseTokenHash(),request.leaseTokenHash()) || !equal(attempt.claimBodySha256(),digest)
                    || !job.profileRevision().equals(request.profileRevision())) throw error(OcrStateError.CLAIM_CONFLICT);
            Instant now = state.wallClock(); // Deliberately after both row locks, never transaction_timestamp().
            live(job,attempt,now);
            return Optional.of(claimView(job,attempt,now));
        }
        var ready = state.lockReady(request.profileRevision());
        if (ready.isEmpty()) return Optional.empty();
        OcrJobState job = ready.get();
        Instant now = state.wallClock();
        if (job.attemptsStarted() >= budget(job)) {
            state.finishJob(job,JobStatus.FAILED,OcrEndReason.RETRIES_EXHAUSTED,"RETRIES_EXHAUSTED",now);
            return Optional.empty();
        }
        if (now.isBefore(job.nextAttemptAt())) return Optional.empty();
        Instant deadline = now.plus(properties.getAttemptTimeout());
        OcrAttemptState attempt = state.insertAttempt(job,request,digest,now,
                now.plus(properties.getLeaseDuration()),deadline,deadline.plus(properties.getQuarantineGrace()));
        state.claimed(job,attempt,now);
        if (!state.wallClock().isBefore(attempt.leaseExpiresAt())) throw error(OcrStateError.STALE_ATTEMPT);
        return Optional.of(claimView(job,attempt,now));
    }

    @Transactional
    public OcrClaim heartbeat(OcrAttemptKey key) {
        OcrAttemptState found = find(key);
        OcrJobState job = job(found.jobId());
        OcrAttemptState attempt = state.lockAttempt(found.id());
        proof(key,attempt);
        Instant now = state.wallClock();
        live(job,attempt,now);
        Instant expiry = min(now.plus(properties.getLeaseDuration()),attempt.hardDeadlineAt());
        state.renewed(attempt.id(),expiry);
        return new OcrClaim(job.id(),job.documentId(),job.profileRevision(),attempt.publicId(),attempt.attemptNo(),now,expiry,attempt.hardDeadlineAt());
    }

    @Transactional
    public OcrReceipt complete(OcrAttemptKey key,String submissionJson) {
        // Parsing/validation may be slow; no lease timestamp is captured before the locks.
        OcrResultPayload result = results.parse(submissionJson);
        OcrAttemptState found = find(key);
        OcrJobState job = job(found.jobId());
        OcrAttemptState attempt = state.lockAttempt(found.id());
        proof(key,attempt);
        if (attempt.status() == OcrAttemptStatus.ACCEPTED) {
            sameOutcome(attempt,result.digest());
            return new OcrReceipt(job.id(),attempt.publicId(),state.extractionId(attempt.id()),true);
        }
        if (attempt.status() == OcrAttemptStatus.FAILED) throw error(OcrStateError.OUTCOME_CONFLICT);
        live(job,attempt,state.wallClock());
        if (!job.profileRevision().equals(result.profileRevision()) || !Long.toString(job.documentId()).equals(result.documentId())
                || !("a_" + attempt.publicId().toString().replace("-","")).equals(result.requestId())) throw error(OcrStateError.INVALID_RESULT);
        long extractionId = state.insertResult(job,attempt,result);
        JobStatus status = switch (result.status()) { case "success" -> JobStatus.COMPLETED; case "partial" -> JobStatus.PARTIAL; default -> JobStatus.FAILED; };
        OcrEndReason reason = switch (status) { case COMPLETED -> OcrEndReason.RESULT_SUCCESS; case PARTIAL -> OcrEndReason.RESULT_PARTIAL; default -> results.failedReason(result); };
        Instant now = state.wallClock();
        live(job,attempt,now);
        String code = status == JobStatus.FAILED ? results.failedCode(result) : null;
        state.finishAttempt(attempt.id(),OcrAttemptStatus.ACCEPTED,reason,code,result.digest(),now);
        state.finishJob(job,status,reason,code,now);
        // A blocked document update must not let a pre-expiry check admit a post-expiry commit.
        live(job,attempt,state.wallClock());
        return new OcrReceipt(job.id(),attempt.publicId(),extractionId,false);
    }

    @Transactional
    public OcrReceipt fail(OcrAttemptKey key,OcrFailureCode code) {
        if (code == null || code == OcrFailureCode.LEASE_EXPIRED) throw error(OcrStateError.INVALID_REQUEST);
        String digest = OcrResultValidator.digest("failure:" + code.name());
        OcrAttemptState found = find(key);
        OcrJobState job = job(found.jobId());
        OcrAttemptState attempt = state.lockAttempt(found.id());
        proof(key,attempt);
        if (attempt.status() == OcrAttemptStatus.FAILED) {
            sameOutcome(attempt,digest);
            return new OcrReceipt(job.id(),attempt.publicId(),null,true);
        }
        if (attempt.status() == OcrAttemptStatus.ACCEPTED) throw error(OcrStateError.OUTCOME_CONFLICT);
        Instant now = state.wallClock();
        live(job,attempt,now);
        OcrEndReason reason = code == OcrFailureCode.CONFIGURATION_ERROR ? OcrEndReason.CONFIGURATION_ERROR : OcrEndReason.INFRA_FAILURE;
        state.finishAttempt(attempt.id(),OcrAttemptStatus.FAILED,reason,code.name(),digest,now);
        retryOrFinish(job,attempt,code,reason,now);
        live(job,attempt,state.wallClock());
        return new OcrReceipt(job.id(),attempt.publicId(),null,false);
    }

    /** Invoked explicitly by a trusted caller in M7.2; no scheduled execution is registered. */
    @Transactional
    public int expireDue() {
        int expired = 0;
        for (long id : state.lockExpiredJobs(properties.getExpiryBatchSize())) {
            OcrJobState job = job(id);
            OcrAttemptState attempt = state.lockAttempt(job.currentAttemptId());
            Instant now = state.wallClock();
            if (attempt.status() != OcrAttemptStatus.ACTIVE || now.isBefore(attempt.leaseExpiresAt())) continue;
            state.finishAttempt(attempt.id(),OcrAttemptStatus.EXPIRED,OcrEndReason.LEASE_EXPIRED,
                    OcrFailureCode.LEASE_EXPIRED.name(),null,now);
            retryOrFinish(job,attempt,OcrFailureCode.LEASE_EXPIRED,OcrEndReason.LEASE_EXPIRED,now);
            expired++;
        }
        return expired;
    }

    private void retryOrFinish(OcrJobState job,OcrAttemptState attempt,OcrFailureCode code,OcrEndReason reason,Instant now) {
        if (code.isRetryable() && job.attemptsStarted() < budget(job)) {
            Instant backoff = now.plus(properties.getRetryBackoffs().get(attempt.attemptNo()-1));
            state.retry(job,code.name(),max(backoff,attempt.reclaimAfter()));
        } else {
            OcrEndReason end = code.isRetryable() ? OcrEndReason.RETRIES_EXHAUSTED : reason;
            state.finishJob(job,JobStatus.FAILED,end,code.name(),now);
        }
    }
    private OcrAttemptState find(OcrAttemptKey key) {
        return state.findAttempt(key.attemptId()).orElseThrow(() -> error(OcrStateError.NOT_FOUND));
    }
    private OcrJobState job(long id) {
        OcrJobState job = state.lockJob(id).orElseThrow(() -> error(OcrStateError.NOT_FOUND));
        if (job.profileRevision() == null) throw error(OcrStateError.INVALID_REQUEST);
        return job;
    }
    private int budget(OcrJobState job) { return Math.min(job.maxAttempts(),properties.getMaxAttempts()); }
    private static void proof(OcrAttemptKey key,OcrAttemptState attempt) {
        if (!key.workerId().equals(attempt.workerId()) || !equal(key.leaseTokenHash(),attempt.leaseTokenHash())) throw error(OcrStateError.STALE_ATTEMPT);
    }
    private static void live(OcrJobState job,OcrAttemptState attempt,Instant now) {
        if (job.status() != JobStatus.PROCESSING || job.currentAttemptId() == null || job.currentAttemptId() != attempt.id()
                || attempt.status() != OcrAttemptStatus.ACTIVE || !now.isBefore(attempt.leaseExpiresAt())
                || !now.isBefore(attempt.hardDeadlineAt())) throw error(OcrStateError.STALE_ATTEMPT);
    }
    private static void sameOutcome(OcrAttemptState attempt,String digest) {
        if (!equal(attempt.outcomeSha256(),digest)) throw error(OcrStateError.OUTCOME_CONFLICT);
    }
    private static boolean equal(String first,String second) {
        return first != null && second != null && MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),second.getBytes(StandardCharsets.US_ASCII));
    }
    private static OcrClaim claimView(OcrJobState job,OcrAttemptState attempt,Instant now) {
        return new OcrClaim(job.id(),job.documentId(),job.profileRevision(),attempt.publicId(),attempt.attemptNo(),now,attempt.leaseExpiresAt(),attempt.hardDeadlineAt());
    }
    private static OcrStateException error(OcrStateError reason) { return new OcrStateException(reason); }
    private static Instant min(Instant a,Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant max(Instant a,Instant b) { return a.isAfter(b) ? a : b; }
}
