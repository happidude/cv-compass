package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.mapper.CandidateValueMapper;
import com.cvcompass.backend.candidate.mapping.PersonalInfoMapper;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.candidate.repository.PublicationRepository;
import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PublicationService {
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
            ObjectMapper json) {
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
        var body = json.readTree(result.body());
        boolean v3 =
                result.schemaVersion() == 3
                        && body.path("schemaVersion").asInt() == 3
                        && ("personal-info-mapping/3.0"
                                        .equals(body.path("engineVersion").asString())
                                || "personal-info-mapping/3.1"
                                        .equals(body.path("engineVersion").asString())
                                || "personal-info-mapping/3.2"
                                        .equals(body.path("engineVersion").asString())
                                || "personal-info-mapping/3.3"
                                        .equals(body.path("engineVersion").asString())
                                || "personal-info-mapping/3.4"
                                        .equals(body.path("engineVersion").asString())
                                || "personal-info-mapping/3.5"
                                        .equals(body.path("engineVersion").asString()));
        var personal = PersonalInfoMapper.patch(body, json.createObjectNode());
        boolean usable =
                (result.schemaVersion() == 2 || v3)
                        && "NEEDS_REVIEW".equals(result.quality())
                        && (body.path("schemaVersion").asInt() == 2 || v3)
                        && body.path("documentId").asLong() == result.documentId()
                        && body.path("extractionId").asLong() == result.extractionId()
                        && ("experience-mapping/2.0".equals(body.path("engineVersion").asString())
                                || v3)
                        && body.path("experiences").isArray()
                        && (!body.path("experiences").isEmpty() || (v3 && !personal.isEmpty()));
        // Serialize skipped rows too, without manufacturing a candidate identity.
        documents.lock(result.documentId());
        if (publications.exists(resultId)) return false;
        if (!usable) {
            publications.ledger(result, null, "SKIPPED", skipReason(result, body, v3));
            return true;
        }
        boolean stale = publications.stale(result);
        if (stale) {
            publications.ledger(result, null, "SUPERSEDED");
            return true;
        }
        long id = documents.lockAndResolve(result.documentId());
        publications.ensureProfile(id);
        long version = revisions.lock(id);
        var before = queries.detail(id);
        boolean initial = repository.experienceCount(id) == 0 && version == 0;
        publications.ledger(result, id, initial ? "PUBLISHED" : "PROPOSED");
        boolean changed = false;
        for (var experience : body.path("experiences")) {
            changed = true;
            if (initial)
                experiences.insertExperience(
                        id, experience, "UNVERIFIED", "MAPPING", result.documentId(), result.id());
            else publications.propose(UUID.randomUUID(), id, result, experience);
        }
        if (v3) {
            var patch = PersonalInfoMapper.patch(body, before.basic());
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
        return true;
    }

    private static String skipReason(
            CompletedMappings.Result result, tools.jackson.databind.JsonNode body, boolean v3) {
        if (!(result.schemaVersion() == 2 || v3)
                || body.path("schemaVersion").asInt() != result.schemaVersion()
                || !(v3 || "experience-mapping/2.0".equals(body.path("engineVersion").asString())))
            return "UNSUPPORTED_MAPPING_VERSION";
        if (!"NEEDS_REVIEW".equals(result.quality())) return "UNSUPPORTED_MAPPING_QUALITY";
        if (body.path("documentId").asLong() != result.documentId()
                || body.path("extractionId").asLong() != result.extractionId())
            return "MAPPING_SOURCE_MISMATCH";
        if (!body.path("experiences").isArray()) return "INVALID_MAPPING_ENVELOPE";
        return "NO_USABLE_FIELDS";
    }
}
