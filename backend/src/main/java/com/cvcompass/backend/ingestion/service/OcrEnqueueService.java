package com.cvcompass.backend.ingestion.service;

import static com.cvcompass.backend.web.OperationalLog.Event.*;
import static org.slf4j.event.Level.INFO;

import com.cvcompass.backend.ingestion.config.*;
import com.cvcompass.backend.ingestion.entity.*;
import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.repository.CvProcessingJobRepository;
import com.cvcompass.backend.ingestion.repository.OcrStateRepository;
import com.cvcompass.backend.web.OperationalLog;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Participates in the metadata transaction; failure triggers existing storage compensation. */
@Service
public class OcrEnqueueService {
    private final OcrIntegrationProperties integration;
    private final OcrJobProperties jobs;
    private final CvProcessingJobRepository repository;
    private final ObjectMapper json;
    private final OcrStateRepository state;
    private final OcrAdmissionPolicy admission;

    public OcrEnqueueService(
            OcrIntegrationProperties integration,
            OcrJobProperties jobs,
            CvProcessingJobRepository repository,
            ObjectMapper json,
            OcrStateRepository state,
            OcrAdmissionPolicy admission) {
        this.integration = integration;
        this.jobs = jobs;
        this.repository = repository;
        this.json = json;
        this.state = state;
        this.admission = admission;
        if (integration.isEnabled()) integration.getProfiles().values().forEach(this::engines);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(CvDocument document) {
        OperationalLog.committed(
                INFO, DOCUMENT_STORED, null, Map.of("documentId", document.getId()));
        if (!integration.isEnabled()) {
            notEnqueued(document, "INTEGRATION_DISABLED");
            return;
        }
        var decision = admission.admit(document);
        if (decision.isEmpty()) {
            notEnqueued(document, "NOT_ADMITTED");
            return;
        }
        var enrolled = decision.get();
        var profile = integration.getProfiles().get(enrolled.profileRevision());
        var job = new CvProcessingJob();
        job.setDocument(document);
        job.setStatus(JobStatus.PENDING);
        job.setProfileRevision(enrolled.profileRevision());
        job.setGeneration(1);
        job.setMaxAttempts(jobs.getMaxAttempts());
        job.setAttemptsStarted(0);
        job.setNextAttemptAt(state.wallClock());
        var snapshot =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of(
                                "namespace",
                                integration.getNamespace(),
                                "document_id",
                                document.getId().toString(),
                                "sha256",
                                document.getSha256(),
                                "size_bytes",
                                document.getFileSize(),
                                "worker_commit",
                                profile.getWorkerCommit(),
                                "image_id",
                                profile.getImageId(),
                                "config_fingerprint",
                                profile.getConfigFingerprint(),
                                "engines",
                                json.convertValue(
                                        engines(profile),
                                        new tools.jackson.core.type.TypeReference<
                                                Map<String, Object>>() {})));
        snapshot.put("admission_mode", enrolled.mode());
        snapshot.put("result_version", profile.getResultVersion());
        if (enrolled.fixtureId() != null) snapshot.put("fixture_id", enrolled.fixtureId());
        job.setAdmissionSnapshot(snapshot);
        repository.saveAndFlush(job);
        var eventContext = new java.util.HashMap<String, Object>();
        eventContext.put("documentId", document.getId());
        eventContext.put("jobId", job.getId());
        eventContext.put("status", "PENDING");
        OperationalLog.committed(INFO, OCR_ENQUEUED, null, eventContext);
    }

    private void notEnqueued(CvDocument document, String reason) {
        OperationalLog.committed(
                INFO, OCR_NOT_ENQUEUED, reason, Map.of("documentId", document.getId()));
    }

    private JsonNode engines(OcrIntegrationProperties.Profile profile) {
        try {
            String raw = profile.getEnginesJson();
            if (raw == null || raw.length() > 16384) throw new IllegalArgumentException();
            JsonNode tree =
                    json.reader()
                            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(raw);
            Set<String> versions =
                    Set.of(
                            "python",
                            "pdfplumber",
                            "pdfminer_six",
                            "pypdfium2",
                            "pdfium",
                            "tesseract",
                            "leptonica",
                            "pillow",
                            "worker_version");
            var keys = new HashSet<>(versions);
            keys.add("models");
            if (!tree.isObject() || !Set.copyOf(tree.propertyNames()).equals(keys))
                throw new IllegalArgumentException();
            for (String name : versions)
                if (!tree.get(name).isString()
                        || tree.get(name).asString().isBlank()
                        || tree.get(name).asString().length() > 128)
                    throw new IllegalArgumentException();
            JsonNode models = tree.get("models");
            var names = new HashSet<String>();
            if (!models.isArray() || models.size() != 3) throw new IllegalArgumentException();
            for (JsonNode model : models) {
                if (!model.isObject()
                        || !Set.copyOf(model.propertyNames())
                                .equals(Set.of("name", "revision", "sha256"))
                        || !Set.of("vie", "eng", "osd").contains(model.path("name").asString())
                        || !names.add(model.path("name").asString())
                        || !model.path("revision").isString()
                        || !model.path("revision").asString().matches("[0-9a-f]{40}")
                        || !model.path("sha256").isString()
                        || !model.path("sha256").asString().matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException();
            }
            return tree;
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("Invalid OCR deployment registry");
        }
    }
}
