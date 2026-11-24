package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.events.CandidateChanged;
import com.cvcompass.backend.candidate.mapper.CandidateValueMapper;
import com.cvcompass.backend.candidate.mapping.PersonalInfoMapper;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.candidate.repository.PublicationRepository;
import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import com.cvcompass.backend.web.OperationalLog;
import java.util.HashMap;
import java.util.UUID;
import org.slf4j.event.Level;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PublicationService {
    private final ApplicationEventPublisher events;
    private final PublicationRepository publications;
    private final CandidateProfileRepository repository;
    private final CompletedMappings mappings;
    private final CandidateDocuments documents;
    private final CandidateQueryService queries;
    private final CandidateRevisionService revisions;
    private final CandidateExperienceService experiences;
    private final ObjectMapper json;

    public PublicationService(
            PublicationRepository publications,
            CandidateProfileRepository repository,
            CompletedMappings mappings,
            CandidateDocuments documents,
            CandidateQueryService queries,
            CandidateRevisionService revisions,
            CandidateExperienceService experiences,
            ObjectMapper json,
            ApplicationEventPublisher events) {
        this.events = events;
        this.publications = publications;
        this.repository = repository;
        this.mappings = mappings;
        this.documents = documents;
        this.queries = queries;
        this.revisions = revisions;
        this.experiences = experiences;
        this.json = json;
    }

    @Transactional
    public boolean publish(long resultId) {
        var result = mappings.read(resultId).orElse(null);
        if (result == null) return false;
        // Same-document row lock serializes identity resolution, order checks and publication.
        if (!documents.valid(result.documentId())) return false;
        // Serialize skipped rows too, and replay ledger entries without reassessing their body.
        documents.lock(result.documentId());
        if (publications.exists(resultId)) return false;
        var mapping = mappings.publishable(result).orElse(null);
        if (mapping == null) {
            publications.ledger(
                    result,
                    null,
                    "SKIPPED",
                    mappings.supportsPublicationVersion(result)
                            ? "INVALID_MAPPING_ENVELOPE"
                            : "UNSUPPORTED_MAPPING_VERSION");
            logPublication(
                    result,
                    "SKIPPED",
                    mappings.supportsPublicationVersion(result)
                            ? "INVALID_MAPPING_ENVELOPE"
                            : "UNSUPPORTED_MAPPING_VERSION");
            return true;
        }
        var body = json.readTree(result.body());
        boolean v3 = mapping.personalInfo();
        var personal =
                PersonalInfoMapper.patch(
                        body, json.createObjectNode(), mapping.canonicalPersonalValues());
        boolean usable =
                "NEEDS_REVIEW".equals(result.quality())
                        && body.path("documentId").asLong() == result.documentId()
                        && body.path("extractionId").asLong() == result.extractionId()
                        && body.path("experiences").isArray()
                        && (!body.path("experiences").isEmpty() || (v3 && !personal.isEmpty()));
        if (!usable) {
            publications.ledger(result, null, "SKIPPED", skipReason(result, body));
            logPublication(result, "SKIPPED", skipReason(result, body));
            return true;
        }
        boolean stale = publications.stale(result);
        if (stale) {
            publications.ledger(result, null, "SUPERSEDED");
            logPublication(result, "SKIPPED", "SUPERSEDED");
            return true;
        }
        long id = documents.lockAndResolve(result.documentId());
        publications.ensureProfile(id);
        long version = revisions.lock(id);
        var before = queries.detail(id);
        boolean initial = repository.experienceCount(id) == 0 && version == 0;
        publications.ledger(result, id, initial ? "PUBLISHED" : "PROPOSED");
        logPublication(result, initial ? "PUBLISHED" : "PROPOSED", null);
        boolean changed = false;
        for (var experience : body.path("experiences")) {
            changed = true;
            if (initial)
                experiences.insertExperience(
                        id, experience, "UNVERIFIED", "MAPPING", result.documentId(), result.id());
            else publications.propose(UUID.randomUUID(), id, result, experience);
        }
        if (v3) {
            var patch =
                    PersonalInfoMapper.patch(
                            body, before.basic(), mapping.canonicalPersonalValues());
            if (!patch.isEmpty()) {
                var metadata = PersonalInfoMapper.metadata(body, result.documentId(), result.id());
                if (initial
                        && "UNVERIFIED".equals(before.basicState())
                        && "MAPPING".equals(before.basicOrigin())
                        && before.basic().isEmpty()) {
                    var merged = PersonalInfoMapper.merge(before.basic(), patch);
                    repository.mappedBasic(
                            id,
                            merged,
                            new CandidateValueMapper().basic(merged),
                            "UNVERIFIED",
                            "MAPPING",
                            PersonalInfoMapper.extensions(
                                    before.extensions(),
                                    metadata,
                                    metadata.path("fields").propertyNames(),
                                    false));
                    changed = true;
                } else
                    changed |=
                            publications.proposePersonal(
                                    id,
                                    result,
                                    json.createObjectNode()
                                            .set("patch", patch)
                                            .set("metadata", metadata));
            }
        }
        if (!changed) return true;
        publications.extensions(
                id,
                json.createObjectNode().put("schemaVersion", 1).set("mapping", body).toString());
        revisions.revision(id, version, "SYSTEM", "PUBLISH_MAPPING", before, queries.detail(id));
        events.publishEvent(new CandidateChanged(id));
        return true;
    }

    private void logPublication(CompletedMappings.Result result, String status, String code) {
        var context = new HashMap<String, Object>();
        context.put("documentId", result.documentId());
        Long jobId = mappings.jobId(result.id());
        if (jobId != null) context.put("jobId", jobId);
        context.put("status", status);
        OperationalLog.committed(
                Level.INFO, OperationalLog.Event.CANDIDATE_PUBLICATION_RECORDED, code, context);
    }

    private static String skipReason(
            CompletedMappings.Result result, tools.jackson.databind.JsonNode body) {
        if (!"NEEDS_REVIEW".equals(result.quality())) return "UNSUPPORTED_MAPPING_QUALITY";
        if (body.path("documentId").asLong() != result.documentId()
                || body.path("extractionId").asLong() != result.extractionId())
            return "MAPPING_SOURCE_MISMATCH";
        if (!body.path("experiences").isArray()) return "INVALID_MAPPING_ENVELOPE";
        return "NO_USABLE_FIELDS";
    }
}
