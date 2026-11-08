package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.dto.History;
import com.cvcompass.backend.candidate.exception.CandidateFailure;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CandidateRevisionService {
    private final CandidateProfileRepository repository;
    private final CandidateQueryService queries;
    private final ObjectMapper json;

    public CandidateRevisionService(
            CandidateProfileRepository repository,
            CandidateQueryService queries,
            ObjectMapper json) {
        this.repository = repository;
        this.queries = queries;
        this.json = json;
    }

    public long lock(long id) {
        var rows = repository.lock(id);
        if (rows.isEmpty()) throw new CandidateFailure(404, "CANDIDATE_NOT_FOUND");
        return rows.getFirst();
    }

    public void expected(long id, long version) {
        if (lock(id) != version) throw new CandidateFailure(409, "VERSION_CONFLICT");
    }

    public void revision(
            long id, long version, String actor, String action, Object before, Object after) {
        JsonNode snapshot = snapshot(after);
        if (snapshot instanceof ObjectNode object) {
            if (object.has("version")) object.put("version", version + 1);
            else if (object.path("profile") instanceof ObjectNode profile)
                profile.put("version", version + 1);
        }
        repository.revision(
                id,
                version,
                actor,
                action,
                before == null ? null : snapshot(before).toString(),
                snapshot == null ? null : snapshot.toString());
    }

    private JsonNode snapshot(Object value) {
        if (value == null) return null;
        JsonNode snapshot = json.valueToTree(value);
        // Derived summaries belong to profile responses, not persisted history snapshots.
        if (snapshot instanceof ObjectNode object) {
            object.remove("experienceSummary");
            if (object.path("profile") instanceof ObjectNode profile)
                profile.remove("experienceSummary");
        }
        return snapshot;
    }

    public List<History> history(long id, Long before, int size) {
        queries.requireExists(id);
        if (size < 1 || size > 100) throw new CandidateFailure(400, "VALIDATION_ERROR");
        return repository.history(id, before, size);
    }
}
