package com.cvcompass.backend.mapping.service;

import static com.cvcompass.backend.mapping.remote.MappingRemoteException.require;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.config.MappingProperties;
import com.cvcompass.backend.mapping.config.MappingRemoteProperties;
import com.cvcompass.backend.mapping.dto.LlmExtractionDefinition;
import com.cvcompass.backend.mapping.remote.MappingRemoteException;
import com.cvcompass.backend.mapping.remote.MappingRemoteJson;
import com.cvcompass.backend.mapping.repository.MappingRemoteRepository;
import com.cvcompass.backend.mapping.repository.MappingRemoteRepository.Attempt;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import com.cvcompass.backend.web.OperationalLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MappingRemoteService {
    public static final Set<String> WORKER_FAILURES =
            Set.of(
                    "PROVIDER_TIMEOUT",
                    "PROVIDER_ERROR",
                    "INVALID_OUTPUT",
                    "INVALID_INPUT",
                    "RESULT_TOO_LARGE",
                    "UNSUPPORTED_ENGINE",
                    "WORKER_SHUTDOWN",
                    "INTERNAL_ERROR");
    private final MappingRemoteRepository attempts;
    private final MappingRepository jobs;
    private final OcrExtractionReader inputs;
    private final MappingRemoteProperties properties;
    private final MappingProperties mapping;
    private final ObjectMapper json;
    private final MappingRemoteVerifier verifier;
    private final TransactionTemplate tx;

    public MappingRemoteService(
            MappingRemoteRepository attempts,
            MappingRepository jobs,
            OcrExtractionReader inputs,
            MappingRemoteProperties properties,
            MappingProperties mapping,
            ObjectMapper json,
            MappingRemoteVerifier verifier,
            PlatformTransactionManager transactions) {
        this.attempts = attempts;
        this.jobs = jobs;
        this.inputs = inputs;
        this.properties = properties;
        this.mapping = mapping;
        this.json = json;
        this.verifier = verifier;
        this.tx = new TransactionTemplate(transactions);
    }

    public Reply claim(String worker, UUID request, String tokenHash, String raw) {
        enabled();
        JsonNode body = MappingRemoteJson.parse(raw, json);
        MappingRemoteJson.exact(body, Set.of("protocol", "engineVersions", "provenance"));
        MappingRemoteJson.protocol(body);
        JsonNode engines = body.path("engineVersions");
        require(
                engines.isArray()
                        && engines.size() == 1
                        && engines.get(0).isString()
                        && LlmExtractionDefinition.ENGINE_VERSION.equals(engines.get(0).asString()),
                422,
                "INVALID_RESULT");
        JsonNode provenance = MappingRemoteJson.provenance(body.path("provenance"));
        String hash = MappingDigests.sha256(raw);
        return tx.execute(
                status -> {
                    attempts.lockRequest(worker, request);
                    var replay = attempts.replay(worker, request);
                    if (replay.isPresent()) {
                        Attempt attempt = owned(worker, replay.get());
                        require(hash.equals(attempt.claimHash()), 409, "IDEMPOTENCY_CONFLICT");
                        verifyToken(attempt, tokenHash);
                        return new Reply(200, claimData(attempt));
                    }
                    var ready = attempts.ready();
                    if (ready.isEmpty()) return new Reply(204, Map.of());
                    long job = ready.get();
                    var claim = jobs.readClaim(job);
                    var input = inputs.find(claim.extractionId());
                    if (input.isEmpty() || !jobs.pinInput(job, fingerprint(input.get()))) {
                        // Consume the broken head of the queue without rolling its retry back.
                        attempts.consumeInputFailure(job);
                        var failed = jobs.lockJob(job);
                        jobs.fail(
                                job,
                                failed.attempts(),
                                failed.maxAttempts(),
                                "INPUT_CHANGED",
                                true,
                                mapping.getRetryBackoff());
                        OperationalLog.committed(
                                Level.WARN,
                                OperationalLog.Event.MAPPING_REMOTE_FAILED,
                                "INPUT_CHANGED",
                                Map.of("documentId", jobs.documentId(job), "jobId", job));
                        return new Reply(204, Map.of());
                    }
                    attempts.start(
                            job,
                            UUID.randomUUID(),
                            properties.getLeaseDuration(),
                            properties.getHardDeadline());
                    UUID id = UUID.randomUUID();
                    attempts.insert(
                            job,
                            id,
                            worker,
                            request,
                            hash,
                            tokenHash,
                            json.writeValueAsString(provenance),
                            properties.getLeaseDuration(),
                            properties.getReclaimGrace());
                    Attempt attempt = attempts.lock(id);
                    live(attempt);
                    log(attempt, OperationalLog.Event.MAPPING_REMOTE_CLAIMED, null);
                    return new Reply(201, claimData(attempt));
                });
    }

    private Map<String, Object> claimData(Attempt attempt) {
        var claim = jobs.readClaim(attempt.jobId());
        return Map.of(
                "attemptId",
                attempt.id(),
                "engineVersion",
                claim.engineVersion(),
                "inputSha256",
                attempt.inputHash(),
                "leaseExpiresAt",
                attempt.lease(),
                "hardDeadlineAt",
                attempt.deadline(),
                "heartbeatInterval",
                properties.getHeartbeatInterval().toString(),
                "definition",
                claim.definition(),
                "status",
                attempt.status());
    }

    public Map<String, Object> input(String worker, UUID id, String tokenHash) {
        enabled();
        return tx.execute(
                status -> {
                    Attempt attempt = owned(worker, id, tokenHash);
                    live(attempt);
                    Extraction input = currentInput(attempt);
                    var result =
                            Map.<String, Object>of(
                                    "pages",
                                    input.pages().stream()
                                            .map(
                                                    p ->
                                                            Map.of(
                                                                    "page",
                                                                    p.number(),
                                                                    "raw_text",
                                                                    p.rawText() == null
                                                                            ? ""
                                                                            : p.rawText()))
                                            .toList());
                    live(attempt);
                    return result;
                });
    }

    public Map<String, Object> heartbeat(String worker, UUID id, String tokenHash, String raw) {
        enabled();
        MappingRemoteJson.exact(MappingRemoteJson.parse(raw, json), Set.of());
        return tx.execute(
                status -> {
                    Attempt attempt = owned(worker, id, tokenHash);
                    live(attempt);
                    attempts.heartbeat(attempt, properties.getLeaseDuration());
                    Attempt updated = attempts.lock(id);
                    live(updated);
                    return Map.of("leaseExpiresAt", updated.lease());
                });
    }

    public Map<String, Object> result(String worker, UUID id, String tokenHash, String raw) {
        enabled();
        // Return validation errors only after committing the consumed attempt and retry schedule.
        MappingRemoteException rejected =
                tx.execute(
                        status -> {
                            Attempt attempt = owned(worker, id, tokenHash);
                            String digest = MappingDigests.sha256(raw);
                            if (replayed(attempt, digest)) {
                                return attempt.errorCode() == null
                                        ? null
                                        : new MappingRemoteException(
                                                "INPUT_CHANGED".equals(attempt.errorCode())
                                                        ? 409
                                                        : 422,
                                                attempt.errorCode());
                            }
                            live(attempt);
                            try {
                                var input = currentInput(attempt);
                                var body = MappingRemoteJson.parse(raw, json);
                                var result =
                                        verifier.verify(
                                                jobs.readClaim(attempt.jobId()),
                                                input,
                                                attempt.inputHash(),
                                                body,
                                                json.readTree(attempt.provenance()));
                                String envelope = MappingDigests.canonical(result);
                                require(
                                        envelope.getBytes(StandardCharsets.UTF_8).length
                                                <= properties.getMaxResultBytes(),
                                        413,
                                        "RESULT_TOO_LARGE");
                                live(attempt);
                                jobs.finish(
                                        attempt.jobId(),
                                        3,
                                        MappingDigests.sha256(envelope),
                                        result.quality(),
                                        envelope);
                                attempts.end(attempt, "ACCEPTED", null, digest);
                                // This must throw out of the transaction if persistence crossed the
                                // lease.
                                liveTime(attempt);
                                log(attempt, OperationalLog.Event.MAPPING_REMOTE_ACCEPTED, null);
                                return null;
                            } catch (MappingRemoteException invalid) {
                                if (invalid.status() != 422
                                        && !invalid.getMessage().equals("INPUT_CHANGED"))
                                    throw invalid;
                                fail(attempt, "FAILED", invalid.getMessage(), digest, false);
                                return invalid;
                            }
                        });
        if (rejected != null) throw rejected;
        return Map.of("status", "ACCEPTED");
    }

    public Map<String, Object> failure(String worker, UUID id, String tokenHash, String raw) {
        enabled();
        JsonNode body = MappingRemoteJson.parse(raw, json);
        MappingRemoteJson.exact(body, Set.of("code"));
        String code = MappingRemoteJson.string(body.path("code"));
        require(WORKER_FAILURES.contains(code), 422, "INVALID_RESULT");
        return tx.execute(
                status -> {
                    Attempt attempt = owned(worker, id, tokenHash);
                    if (!replayed(attempt, MappingDigests.sha256(raw))) {
                        live(attempt);
                        fail(attempt, "FAILED", code, MappingDigests.sha256(raw), false);
                    }
                    return Map.of("status", "FAILED");
                });
    }

    private boolean replayed(Attempt attempt, String hash) {
        if (attempt.outcomeHash() == null) return false;
        require(hash.equals(attempt.outcomeHash()), 409, "OUTCOME_CONFLICT");
        return true;
    }

    private Attempt owned(String worker, UUID id, String tokenHash) {
        Attempt attempt = owned(worker, id);
        verifyToken(attempt, tokenHash);
        return attempt;
    }

    private Attempt owned(String worker, UUID id) {
        long jobId =
                attempts.jobId(id)
                        .orElseThrow(() -> new MappingRemoteException(409, "STALE_ATTEMPT"));
        jobs.lockJob(jobId);
        Attempt attempt = attempts.lock(id);
        require(worker.equals(attempt.worker()), 409, "STALE_ATTEMPT");
        return attempt;
    }

    private static void verifyToken(Attempt attempt, String tokenHash) {
        require(
                MessageDigest.isEqual(
                        tokenHash.getBytes(StandardCharsets.US_ASCII),
                        attempt.tokenHash().getBytes(StandardCharsets.US_ASCII)),
                409,
                "INVALID_LEASE_TOKEN");
    }

    private void live(Attempt attempt) {
        var job = jobs.lockJob(attempt.jobId());
        require(
                "ACTIVE".equals(attempt.status())
                        && "PROCESSING".equals(job.status())
                        && job.attempts() == attempt.number(),
                409,
                "STALE_ATTEMPT");
        liveTime(attempt);
    }

    private void liveTime(Attempt attempt) {
        var now = jobs.now();
        require(now.isBefore(attempt.deadline()), 409, "HARD_DEADLINE_EXCEEDED");
        require(now.isBefore(attempt.lease()), 409, "LEASE_EXPIRED");
    }

    private Extraction currentInput(Attempt attempt) {
        var claim = jobs.readClaim(attempt.jobId());
        var input =
                inputs.find(claim.extractionId())
                        .orElseThrow(() -> new MappingRemoteException(409, "INPUT_CHANGED"));
        require(fingerprint(input).equals(attempt.inputHash()), 409, "INPUT_CHANGED");
        return input;
    }

    private static String fingerprint(Extraction input) {
        return MappingDigests.sha256(MappingDigests.canonical(input));
    }

    public int reap() {
        if (!properties.isEnabled()) return 0;
        return tx.execute(
                status -> {
                    var expired = attempts.expired(mapping.getReaperPageSize());
                    for (UUID id : expired) {
                        Attempt attempt = attempts.lock(id);
                        if ("ACTIVE".equals(attempt.status())
                                && !jobs.now().isBefore(attempt.lease())) {
                            var job = jobs.lockJob(attempt.jobId());
                            if ("PROCESSING".equals(job.status())
                                    && job.attempts() == attempt.number()) {
                                fail(attempt, "EXPIRED", "LEASE_EXPIRED", null, true);
                            } else {
                                attempts.end(attempt, "EXPIRED", "LEASE_EXPIRED", null);
                                log(
                                        attempt,
                                        OperationalLog.Event.MAPPING_REMOTE_FAILED,
                                        "LEASE_EXPIRED");
                            }
                        }
                    }
                    return expired.size();
                });
    }

    private void fail(
            Attempt attempt, String status, String code, String outcome, boolean quarantine) {
        var job = jobs.lockJob(attempt.jobId());
        jobs.fail(
                attempt.jobId(),
                job.attempts(),
                job.maxAttempts(),
                code,
                true,
                mapping.getRetryBackoff());
        if (quarantine) attempts.quarantine(attempt);
        attempts.end(attempt, status, code, outcome);
        log(attempt, OperationalLog.Event.MAPPING_REMOTE_FAILED, code);
    }

    private void log(Attempt attempt, OperationalLog.Event event, String code) {
        OperationalLog.committed(
                Level.INFO,
                event,
                code,
                Map.of(
                        "documentId", jobs.documentId(attempt.jobId()),
                        "jobId", attempt.jobId(),
                        "attemptId", attempt.id()));
    }

    private void enabled() {
        require(properties.isEnabled(), 503, "MAPPING_REMOTE_UNAVAILABLE");
    }

    public record Reply(int status, Map<String, Object> data) {}
}
