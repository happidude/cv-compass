package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.OcrIntegrationProperties;
import com.cvcompass.backend.ingestion.dto.OcrAttemptKey;
import com.cvcompass.backend.ingestion.dto.OcrClaim;
import com.cvcompass.backend.ingestion.dto.OcrClaimRequest;
import com.cvcompass.backend.ingestion.dto.OcrHttpReply;
import com.cvcompass.backend.ingestion.dto.OcrInput;
import com.cvcompass.backend.ingestion.dto.OcrReceipt;
import com.cvcompass.backend.ingestion.dto.OcrWorkerPrincipal;
import com.cvcompass.backend.ingestion.entity.CvProcessingJob;
import com.cvcompass.backend.ingestion.enums.OcrFailureCode;
import com.cvcompass.backend.ingestion.enums.OcrProtocol;
import com.cvcompass.backend.ingestion.exception.OcrHttpException;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.repository.OcrStateRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated HTTP facade. State machine remains the only writer of attempt outcomes. */
@Service
@ConditionalOnProperty(prefix = "cv.ocr.integration", name = "enabled", havingValue = "true")
public class OcrWorkerService {
    private final OcrJobService jobs;
    private final OcrStateRepository state;
    private final CvProcessingJobRepository records;
    private final OcrIntegrationProperties properties;
    private final ObjectMapper json;

    public OcrWorkerService(
            OcrJobService jobs,
            OcrStateRepository state,
            CvProcessingJobRepository records,
            OcrIntegrationProperties properties,
            ObjectMapper json) {
        this.jobs = jobs;
        this.state = state;
        this.records = records;
        this.properties = properties;
        this.json = json;
    }

    @Transactional
    public OcrHttpReply claim(
            OcrWorkerPrincipal worker, UUID claimId, String tokenHash, String profile) {
        allowed(worker, profile);
        state.lockClaimKey(worker.workerId(), claimId);
        boolean replay = state.findClaim(worker.workerId(), claimId).isPresent();
        var claimed =
                jobs.claim(new OcrClaimRequest(worker.workerId(), claimId, tokenHash, profile));
        if (claimed.isEmpty()) return new OcrHttpReply(204, Map.of());
        OcrClaim claim = claimed.get();
        CvProcessingJob job = admitted(claim.jobId());
        var data = new LinkedHashMap<>(view(claim));
        boolean local = "local".equals(job.getAdmissionSnapshot().get("admission_mode"));
        data.put("profile_revision", profile);
        data.put("synthetic_only", !local);
        if (local) data.put("local_only", true);
        data.put("input_path", "/internal/ocr/v1/attempts/" + claim.attemptId() + "/input");
        data.put("size_bytes", job.getDocument().getFileSize());
        String resultVersion =
                job.getAdmissionSnapshot()
                        .getOrDefault("result_version", OcrProtocol.LEGACY_RESULT_VERSION)
                        .toString();
        data.put(
                "ocr_request",
                Map.of(
                        "type",
                        "request",
                        "schema_version",
                        resultVersion,
                        "request_id",
                        "a_" + claim.attemptId().toString().replace("-", ""),
                        "document_id",
                        Long.toString(claim.documentId()),
                        "input_ref",
                        claim.attemptId().toString().replace("-", "") + ".pdf",
                        "expected_sha256",
                        job.getDocument().getSha256(),
                        "profile",
                        "baseline-v1"));
        return new OcrHttpReply(replay ? 200 : 201, data);
    }

    @Transactional
    public Map<String, Object> heartbeat(OcrWorkerPrincipal worker, OcrAttemptKey key) {
        owner(worker, key);
        return view(jobs.heartbeat(key));
    }

    @Transactional
    public OcrInput input(OcrWorkerPrincipal worker, OcrAttemptKey key) {
        owner(worker, key);
        OcrClaim claim = jobs.authorizeInput(key);
        var document = admitted(claim.jobId()).getDocument();
        return new OcrInput(
                document.getStoragePath(), document.getFileSize(), document.getSha256());
    }

    @Transactional
    public OcrHttpReply result(
            OcrWorkerPrincipal worker, OcrAttemptKey key, String raw, JsonNode envelope) {
        var job = owner(worker, key);
        var snapshot = json.valueToTree(job.getAdmissionSnapshot());
        // Immutable admission snapshot, not a mutable deployment registry, attests this attempt.
        JsonNode result = envelope.path("ocr_result"), provenance = envelope.path("provenance");
        String expectedVersion =
                job.getAdmissionSnapshot()
                        .getOrDefault("result_version", OcrProtocol.LEGACY_RESULT_VERSION)
                        .toString();
        require(
                expectedVersion.equals(result.path("schema_version").asString()),
                422,
                "INVALID_RESULT");
        boolean preflight =
                result.path("page_count").isNull()
                        && "failed".equals(result.path("status").asString());
        require(
                snapshot.path("worker_commit").equals(provenance.path("worker_commit"))
                        && snapshot.path("image_id").equals(provenance.path("image_id")),
                422,
                "INVALID_PROVENANCE");
        JsonNode fingerprint = result.path("config_fingerprint");
        require(
                !fingerprint.isMissingNode()
                        && (preflight && fingerprint.isNull()
                                || fingerprint.equals(snapshot.path("config_fingerprint"))),
                422,
                "INVALID_PROVENANCE");
        JsonNode engines = result.path("engines"), expected = snapshot.path("engines");
        require(engines.isObject() && expected.isObject(), 422, "INVALID_PROVENANCE");
        for (String name : engines.propertyNames()) {
            JsonNode observed = engines.get(name);
            require(
                    preflight
                                    && (observed.isNull()
                                            || name.equals("models")
                                                    && observed.isArray()
                                                    && observed.isEmpty())
                            || observed.equals(expected.path(name)),
                    422,
                    "INVALID_PROVENANCE");
        }
        OcrReceipt receipt = jobs.complete(key, raw);
        var data = new LinkedHashMap<>(receipt(receipt));
        data.put("ocr_status", result.path("status").asString());
        data.put("extraction_id", receipt.extractionId().toString());
        return new OcrHttpReply(receipt.replay() ? 200 : 201, data);
    }

    @Transactional
    public OcrHttpReply failure(OcrWorkerPrincipal worker, OcrAttemptKey key, OcrFailureCode code) {
        owner(worker, key);
        OcrReceipt receipt = jobs.fail(key, code);
        return new OcrHttpReply(receipt.replay() ? 200 : 201, receipt(receipt));
    }

    private CvProcessingJob owner(OcrWorkerPrincipal worker, OcrAttemptKey key) {
        var attempt =
                state.findAttempt(key.attemptId())
                        .orElseThrow(() -> new OcrHttpException(404, "NOT_FOUND"));
        require(worker.workerId().equals(attempt.workerId()), 403, "FORBIDDEN");
        var job =
                records.findById(attempt.jobId())
                        .orElseThrow(() -> new OcrHttpException(404, "NOT_FOUND"));
        allowed(worker, job.getProfileRevision());
        return job;
    }

    private CvProcessingJob admitted(long id) {
        var job =
                records.findById(id)
                        .orElseThrow(() -> new OcrHttpException(503, "ADMISSION_UNAVAILABLE"));
        var snapshot = json.valueToTree(job.getAdmissionSnapshot());
        var document = job.getDocument();
        String mode = snapshot.path("admission_mode").asString("synthetic-only");
        boolean admittedMode =
                "local".equals(mode) && !snapshot.has("fixture_id")
                        || "synthetic-only".equals(mode)
                                && snapshot.path("fixture_id").isString()
                                && !snapshot.path("fixture_id").asString().isEmpty();
        require(
                snapshot.path("namespace").asString().equals(properties.getNamespace())
                        && admittedMode
                        && snapshot.path("document_id")
                                .asString()
                                .equals(document.getId().toString())
                        && snapshot.path("sha256").asString().equals(document.getSha256())
                        && snapshot.path("size_bytes").asLong() == document.getFileSize(),
                503,
                "ADMISSION_UNAVAILABLE");
        return job;
    }

    private static void allowed(OcrWorkerPrincipal worker, String profile) {
        require(worker.profiles().contains(profile), 403, "FORBIDDEN");
    }

    private static Map<String, Object> view(OcrClaim claim) {
        return Map.of(
                "protocol",
                OcrProtocol.JOB_VERSION,
                "job_id",
                Long.toString(claim.jobId()),
                "attempt_id",
                claim.attemptId().toString(),
                "attempt_no",
                claim.attemptNo(),
                "server_time",
                claim.serverTime().toString(),
                "lease_expires_at",
                claim.leaseExpiresAt().toString(),
                "hard_deadline_at",
                claim.hardDeadlineAt().toString());
    }

    private static Map<String, Object> receipt(OcrReceipt receipt) {
        return Map.of(
                "job_id",
                Long.toString(receipt.jobId()),
                "attempt_id",
                receipt.attemptId().toString(),
                "accepted",
                true);
    }

    private static void require(boolean value, int status, String code) {
        if (!value) throw new OcrHttpException(status, code);
    }
}
