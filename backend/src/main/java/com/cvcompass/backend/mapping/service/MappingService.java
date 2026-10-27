package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingDefinition;
import com.cvcompass.backend.mapping.dto.MappingResult;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import com.cvcompass.backend.web.OperationalLog;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class MappingService {
    private final MappingRepository repository;
    private final OcrExtractionReader inputs;
    private final MappingEngine engine;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.repository = repository;
        this.inputs = inputs;
        this.engine = engine;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    public long register(String revision, MappingDefinition definition) {
        if (revision == null || !revision.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid mapping revision");
        }
        definition.validate();
        return tx.execute(
                status ->
                        repository.register(
                                revision,
                                MappingDigests.canonical(definition),
                                MappingDigests.sha256(MappingDigests.canonical(definition))));
    }

    public void activate(long id) {
        tx.executeWithoutResult(
                status -> {
                    repository.lockSettings();
                    var definition = repository.definition(id);
                    definition.validate();
                    repository.activate(
                            id, MappingDigests.sha256(MappingDigests.canonical(definition)));
                });
    }

    /** Each bounded page and its durable cursor commit together; end-of-sweep resets to zero. */
    public int scan(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid scan size");
        return tx.execute(
                status -> {
                    long[] settings = repository.lockSettings();
                    if (settings[0] == 0) return 0;
                    long upperId = settings[2] == 0 ? inputs.upperId() : settings[2];
                    var ids = inputs.scanAfter(settings[1], upperId, limit);
                    for (long id : ids) repository.enqueue(id, settings[0], MappingEngine.VERSION);
                    repository.cursor(
                            ids.size() < limit ? 0 : ids.getLast(),
                            ids.size() < limit ? null : upperId);
                    return ids.size();
                });
    }

    public Optional<MappingClaim> claim() {
        return tx.execute(
                status -> {
                    var claim = repository.claim(MappingEngine.VERSION);
                    claim.ifPresent(
                            value ->
                                    OperationalLog.committed(
                                            Level.INFO,
                                            OperationalLog.Event.MAPPING_CLAIMED,
                                            null,
                                            Map.of(
                                                    "jobId",
                                                    value.jobId(),
                                                    "extractionId",
                                                    value.extractionId())));
                    return claim;
                });
    }

    /** Extraction and CPU processing occur outside the claim/complete transactions. */
    public MappingResult process(MappingClaim claim) {
        MappingClaim current = repository.readClaim(claim.jobId());
        if (!claim.token().equals(current.token())) throw stale();
        var input =
                inputs.find(current.extractionId())
                        .orElseThrow(
                                () -> new IllegalArgumentException("Mapping input unavailable"));
        String fingerprint = MappingDigests.sha256(MappingDigests.canonical(input));
        tx.executeWithoutResult(
                status -> {
                    var job = repository.lockJob(claim.jobId());
                    if (!claim.token().equals(job.token())) throw stale();
                    if (!"COMPLETED".equals(job.status())
                            && (!"PROCESSING".equals(job.status()) || !live(job))) throw stale();
                    if (!repository.pinInput(claim.jobId(), fingerprint)) {
                        throw new IllegalArgumentException("MAPPING_INPUT_CHANGED");
                    }
                });
        return engine.map(current, input);
    }

    public void complete(MappingClaim claim, MappingResult result) {
        // Reconstruct authoritative ownership/config, never trust a caller-modified claim.
        MappingClaim authoritative = repository.readClaim(claim.jobId());
        if (!claim.token().equals(authoritative.token())) throw stale();
        if (!process(authoritative).equals(result)) {
            throw new IllegalStateException("MAPPING_RESULT_CONFLICT");
        }
        String body = MappingDigests.canonical(result);
        if (body.getBytes(StandardCharsets.UTF_8).length > 1048576) {
            throw new IllegalArgumentException("Mapping result exceeds output limit");
        }
        String digest = MappingDigests.sha256(body);
        tx.executeWithoutResult(
                status -> {
                    var job = repository.lockJob(claim.jobId());
                    if (!claim.token().equals(job.token())) throw stale();
                    if ("COMPLETED".equals(job.status())) {
                        if (!digest.equals(repository.digest(claim.jobId()))) {
                            throw new IllegalStateException("MAPPING_RESULT_CONFLICT");
                        }
                        return;
                    }
                    if (!"PROCESSING".equals(job.status()) || !live(job)) throw stale();
                    repository.finish(claim.jobId(), digest, result.quality(), body);
                    // A blocked insert/update must not admit completion after lease expiry.
                    var finished = repository.lockJob(claim.jobId());
                    if (!live(finished)) throw stale();
                    OperationalLog.committed(
                            Level.INFO,
                            OperationalLog.Event.MAPPING_COMPLETED,
                            null,
                            Map.of(
                                    "jobId",
                                    claim.jobId(),
                                    "extractionId",
                                    authoritative.extractionId()));
                });
    }

    public Optional<MappingResult> result(long jobId) {
        return repository.result(jobId).map(body -> json.readValue(body, MappingResult.class));
    }

    public void fail(MappingClaim claim) {
        fail(claim, true);
    }

    public void fail(MappingClaim claim, boolean retryable) {
        tx.executeWithoutResult(
                status -> {
                    var job = repository.lockJob(claim.jobId());
                    if (!claim.token().equals(job.token())
                            || !"PROCESSING".equals(job.status())
                            || !live(job)) throw stale();
                    String code = retryable ? "TEMPORARY_FAILURE" : "PERMANENT_FAILURE";
                    repository.fail(claim.jobId(), job.attempts(), code, retryable);
                    OperationalLog.committed(
                            Level.WARN,
                            OperationalLog.Event.MAPPING_ATTEMPT_FAILED,
                            code,
                            Map.of("jobId", claim.jobId()));
                });
    }

    public int reap(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid reaper size");
        return tx.execute(
                status -> {
                    var ids = repository.expired(limit);
                    for (long id : ids) {
                        var job = repository.lockJob(id);
                        if ("PROCESSING".equals(job.status())
                                && !repository.now().isBefore(job.lease())) {
                            repository.fail(id, job.attempts(), "LEASE_EXPIRED", true);
                            OperationalLog.committed(
                                    Level.WARN,
                                    OperationalLog.Event.MAPPING_ATTEMPT_FAILED,
                                    "LEASE_EXPIRED",
                                    Map.of("jobId", id));
                        }
                    }
                    return ids.size();
                });
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("MAPPING_STALE_ATTEMPT");
    }

    private boolean live(MappingRepository.Job job) {
        var now = repository.now();
        return job.lease() != null
                && job.deadline() != null
                && now.isBefore(job.lease())
                && now.isBefore(job.deadline());
    }
}
