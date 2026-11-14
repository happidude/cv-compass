package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.mapping.config.MappingProperties;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingConfig;
import com.cvcompass.backend.mapping.dto.MappingOutcome;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import com.cvcompass.backend.web.OperationalLog;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class MappingService {
    private final MappingRepository repository;
    private final OcrExtractionReader inputs;
    private final MappingProviderRegistry registry;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final MappingProperties properties;

    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            MappingProperties properties,
            ExperienceEngine experienceEngine) {
        this(
                repository,
                inputs,
                engine,
                json,
                transactions,
                properties,
                experienceEngine,
                new PersonalInfoEngine(experienceEngine));
    }

    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            MappingProperties properties,
            ExperienceEngine experienceEngine,
            PersonalInfoEngine personalInfoEngine) {
        this(
                repository,
                inputs,
                engine,
                json,
                transactions,
                properties,
                experienceEngine,
                personalInfoEngine,
                new PersonalInfoEngineV31());
    }

    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            MappingProperties properties,
            ExperienceEngine experienceEngine,
            PersonalInfoEngine personalInfoEngine,
            PersonalInfoEngineV31 grammarEngine) {
        this(
                repository,
                inputs,
                engine,
                json,
                transactions,
                properties,
                experienceEngine,
                personalInfoEngine,
                grammarEngine,
                new PersonalInfoEngineV32());
    }

    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            MappingProperties properties,
            ExperienceEngine experienceEngine,
            PersonalInfoEngine personalInfoEngine,
            PersonalInfoEngineV31 grammarEngine,
            PersonalInfoEngineV32 grammarV32Engine) {
        this(
                repository,
                inputs,
                engine,
                json,
                transactions,
                properties,
                experienceEngine,
                personalInfoEngine,
                grammarEngine,
                grammarV32Engine,
                new PersonalInfoEngineV33());
    }

    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            MappingProperties properties,
            ExperienceEngine experienceEngine,
            PersonalInfoEngine personalInfoEngine,
            PersonalInfoEngineV31 grammarEngine,
            PersonalInfoEngineV32 grammarV32Engine,
            PersonalInfoEngineV33 grammarV33Engine) {
        this(
                repository,
                inputs,
                engine,
                json,
                transactions,
                properties,
                experienceEngine,
                personalInfoEngine,
                grammarEngine,
                grammarV32Engine,
                grammarV33Engine,
                new PersonalInfoEngineV34());
    }

    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            MappingProperties properties,
            ExperienceEngine experienceEngine,
            PersonalInfoEngine personalInfoEngine,
            PersonalInfoEngineV31 grammarEngine,
            PersonalInfoEngineV32 grammarV32Engine,
            PersonalInfoEngineV33 grammarV33Engine,
            PersonalInfoEngineV34 grammarV34Engine) {
        this(
                repository,
                inputs,
                engine,
                json,
                transactions,
                properties,
                experienceEngine,
                personalInfoEngine,
                grammarEngine,
                grammarV32Engine,
                grammarV33Engine,
                grammarV34Engine,
                new PersonalInfoEngineV35());
    }

    @Autowired
    public MappingService(
            MappingRepository repository,
            OcrExtractionReader inputs,
            MappingEngine engine,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            MappingProperties properties,
            ExperienceEngine experienceEngine,
            PersonalInfoEngine personalInfoEngine,
            PersonalInfoEngineV31 grammarEngine,
            PersonalInfoEngineV32 grammarV32Engine,
            PersonalInfoEngineV33 grammarV33Engine,
            PersonalInfoEngineV34 grammarV34Engine,
            PersonalInfoEngineV35 grammarV35Engine) {
        this.repository = repository;
        this.inputs = inputs;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.properties = properties;
        this.registry =
                new MappingProviderRegistry(
                        List.of(
                                new InProcessMappingProvider(MappingEngine.VERSION, engine::map),
                                new InProcessMappingProvider(
                                        ExperienceEngine.VERSION, experienceEngine::map),
                                new InProcessMappingProvider(
                                        PersonalInfoEngine.VERSION, personalInfoEngine::map),
                                new InProcessMappingProvider(
                                        PersonalInfoEngineV31.VERSION, grammarEngine::map),
                                new InProcessMappingProvider(
                                        PersonalInfoEngineV32.VERSION, grammarV32Engine::map),
                                new InProcessMappingProvider(
                                        PersonalInfoEngineV33.VERSION, grammarV33Engine::map),
                                new InProcessMappingProvider(
                                        PersonalInfoEngineV34.VERSION, grammarV34Engine::map),
                                new InProcessMappingProvider(
                                        PersonalInfoEngineV35.VERSION, grammarV35Engine::map)));
    }

    public long register(String revision, MappingConfig definition) {
        if (revision == null || !revision.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid mapping revision");
        }
        definition.validate();
        if (definition instanceof PersonalInfoDefinition personal
                && !revision.equals(personal.revision()))
            throw new IllegalArgumentException("MAPPING_REVISION_MISMATCH");
        if (MappingDigests.canonical(definition).getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Mapping definition exceeds limit");
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
                    var definition = MappingVersions.config(repository.definitionJson(id), json);
                    definition.validate();
                    repository.activate(
                            id, MappingDigests.sha256(MappingDigests.canonical(definition)));
                });
    }

    /** Each bounded page and its durable cursor commit together; end-of-sweep resets to zero. */
    public int scan(int limit) {
        if (limit < 1 || limit > OcrExtractionReader.MAX_QUERY_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid scan size");
        return tx.execute(
                status -> {
                    long[] settings = repository.lockSettings();
                    if (settings[0] == 0) {
                        OperationalLog.write(
                                Level.WARN,
                                OperationalLog.Event.MAPPING_SCHEDULER_FAILED,
                                "MAPPING_ACTIVE_CONFIG_REQUIRED",
                                Map.of());
                        throw new IllegalStateException("MAPPING_ACTIVE_CONFIG_REQUIRED");
                    }
                    long upperId = settings[2] == 0 ? inputs.upperId() : settings[2];
                    var ids = inputs.scanAfter(settings[1], upperId, limit);
                    String selectedEngine =
                            MappingVersions.config(repository.definitionJson(settings[0]), json)
                                    .engineCompatibility();
                    for (long id : ids)
                        repository.enqueue(
                                id, settings[0], selectedEngine, properties.getMaxAttempts());
                    repository.cursor(
                            ids.size() < limit ? 0 : ids.getLast(),
                            ids.size() < limit ? null : upperId);
                    return ids.size();
                });
    }

    public Optional<MappingClaim> claim() {
        return tx.execute(
                status -> {
                    var claim =
                            repository.claim(
                                    MappingEngine.VERSION,
                                    ExperienceEngine.VERSION,
                                    PersonalInfoEngine.VERSION,
                                    PersonalInfoEngineV31.VERSION,
                                    PersonalInfoEngineV32.VERSION,
                                    PersonalInfoEngineV33.VERSION,
                                    PersonalInfoEngineV34.VERSION,
                                    PersonalInfoEngineV35.VERSION,
                                    properties.getLeaseDuration(),
                                    properties.getHardDeadline());
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
    public MappingOutcome process(MappingClaim claim) {
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
        return registry.require(current.engineVersion()).map(current, input);
    }

    public void complete(MappingClaim claim, MappingOutcome result) {
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
                    repository.finish(
                            claim.jobId(), result.schemaVersion(), digest, result.quality(), body);
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

    public Optional<MappingOutcome> result(long jobId) {
        return repository.result(jobId).map(body -> MappingVersions.result(body, json));
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
                    repository.fail(
                            claim.jobId(),
                            job.attempts(),
                            job.maxAttempts(),
                            code,
                            retryable,
                            properties.getRetryBackoff());
                    OperationalLog.committed(
                            Level.WARN,
                            OperationalLog.Event.MAPPING_ATTEMPT_FAILED,
                            code,
                            Map.of("jobId", claim.jobId()));
                });
    }

    public int reap(int limit) {
        if (limit < 1 || limit > OcrExtractionReader.MAX_QUERY_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid reaper size");
        return tx.execute(
                status -> {
                    var ids = repository.expired(limit);
                    for (long id : ids) {
                        var job = repository.lockJob(id);
                        if ("PROCESSING".equals(job.status())
                                && !repository.now().isBefore(job.lease())) {
                            repository.fail(
                                    id,
                                    job.attempts(),
                                    job.maxAttempts(),
                                    "LEASE_EXPIRED",
                                    true,
                                    properties.getRetryBackoff());
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

    public int scan() {
        return scan(properties.getScanPageSize());
    }

    public int reap() {
        return reap(properties.getReaperPageSize());
    }

    private boolean live(MappingRepository.Job job) {
        var now = repository.now();
        return job.lease() != null
                && job.deadline() != null
                && now.isBefore(job.lease())
                && now.isBefore(job.deadline());
    }
}
