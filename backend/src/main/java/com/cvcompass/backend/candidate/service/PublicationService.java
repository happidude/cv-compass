package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PublicationService {
    private final JdbcTemplate jdbc;
    private final CompletedMappings mappings;
    private final CandidateDocuments documents;
    private final CandidateService profiles;
    private final ObjectMapper json;

    public PublicationService(
            JdbcTemplate jdbc,
            CompletedMappings mappings,
            CandidateDocuments documents,
            CandidateService profiles,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.mappings = mappings;
        this.documents = documents;
        this.profiles = profiles;
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
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?)",
                        Boolean.class,
                        resultId))) return false;
        if (!usable) {
            ledger(result, null, "SKIPPED");
            return true;
        }
        boolean stale =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                """
   SELECT EXISTS(SELECT 1 FROM "CANDIDATE_PUBLICATIONS" WHERE "DOCUMENT_ID"=? AND "STATUS" IN ('PUBLISHED','PROPOSED')
   AND ("EXTRACTION_ID","CONFIG_ID","RESULT_ID")>(?,?,?))
   """,
                                Boolean.class,
                                result.documentId(),
                                result.extractionId(),
                                result.configId(),
                                result.id()));
        if (stale) {
            ledger(result, null, "SUPERSEDED");
            return true;
        }
        long id = documents.lockAndResolve(result.documentId());
        jdbc.update(
                "INSERT INTO \"CANDIDATE_PROFILES\" (\"CANDIDATE_ID\") VALUES (?) ON CONFLICT DO NOTHING",
                id);
        long version = profiles.lock(id);
        var before = profiles.detail(id);
        boolean initial =
                jdbc.queryForObject(
                                        "SELECT count(*) FROM \"CANDIDATE_EXPERIENCES\" WHERE \"CANDIDATE_ID\"=?",
                                        Integer.class,
                                        id)
                                == 0
                        && version == 0;
        ledger(result, id, initial ? "PUBLISHED" : "PROPOSED");
        for (var experience : body.path("experiences")) {
            if (initial)
                profiles.insertExperience(
                        id, experience, "UNVERIFIED", "MAPPING", result.documentId(), result.id());
            else
                jdbc.update(
                        "INSERT INTO \"CANDIDATE_PROPOSALS\" (\"ID\",\"CANDIDATE_ID\",\"RESULT_ID\",\"DOCUMENT_ID\",\"DATA\") VALUES (?,?,?,?,?::jsonb)",
                        UUID.randomUUID(),
                        id,
                        result.id(),
                        result.documentId(),
                        experience.toString());
        }
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"EXTENSIONS\"=?::jsonb WHERE \"CANDIDATE_ID\"=?",
                json.createObjectNode().put("schemaVersion", 1).set("mapping", body).toString(),
                id);
        profiles.revision(id, version, "SYSTEM", "PUBLISH_MAPPING", before, profiles.detail(id));
        return true;
    }

    private void ledger(CompletedMappings.Result result, Long candidate, String status) {
        jdbc.update(
                "INSERT INTO \"CANDIDATE_PUBLICATIONS\" (\"RESULT_ID\",\"CANDIDATE_ID\",\"DOCUMENT_ID\",\"EXTRACTION_ID\",\"CONFIG_ID\",\"STATUS\") VALUES (?,?,?,?,?,?)",
                result.id(),
                candidate,
                result.documentId(),
                result.extractionId(),
                result.configId(),
                status);
    }
}
