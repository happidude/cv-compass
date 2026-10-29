package com.cvcompass.backend.candidate.service;

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
        boolean usable =
                result.schemaVersion() == 2
                        && "NEEDS_REVIEW".equals(result.quality())
                        && body.path("schemaVersion").asInt() == 2
                        && body.path("documentId").asLong() == result.documentId()
                        && body.path("extractionId").asLong() == result.extractionId()
                        && "experience-mapping/2.0".equals(body.path("engineVersion").asString())
                        && body.path("experiences").isArray()
                        && !body.path("experiences").isEmpty();
        // Serialize skipped rows too, without manufacturing a candidate identity.
        documents.lock(result.documentId());
        if (publications.exists(resultId)) return false;
        if (!usable) {
            publications.ledger(result, null, "SKIPPED");
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
        for (var experience : body.path("experiences")) {
            if (initial)
                experiences.insertExperience(
                        id, experience, "UNVERIFIED", "MAPPING", result.documentId(), result.id());
            else publications.propose(UUID.randomUUID(), id, result, experience);
        }
        publications.extensions(
                id,
                json.createObjectNode().put("schemaVersion", 1).set("mapping", body).toString());
        revisions.revision(id, version, "SYSTEM", "PUBLISH_MAPPING", before, queries.detail(id));
        return true;
    }
}
