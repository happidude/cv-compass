package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.dto.Page;
import com.cvcompass.backend.candidate.dto.Profile;
import com.cvcompass.backend.candidate.dto.Proposal;
import com.cvcompass.backend.candidate.exception.CandidateFailure;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CandidateQueryService {
    private final CandidateProfileRepository repository;
    private final CandidateDocuments documents;

    public CandidateQueryService(
            CandidateProfileRepository repository, CandidateDocuments documents) {
        this.repository = repository;
        this.documents = documents;
    }

    public Page list(Long before, int size) {
        if (size < 1 || size > 100 || before != null && before < 1)
            throw new CandidateFailure(400, "VALIDATION_ERROR");
        var rows = repository.list(before, size);
        boolean next = rows.size() > size;
        var content = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        return new Page(content, next, next ? content.getLast().id() : null);
    }

    public Profile detail(long id) {
        var rows = repository.detail(id);
        if (rows.isEmpty()) throw new CandidateFailure(404, "CANDIDATE_NOT_FOUND");
        var row = rows.getFirst();
        return new Profile(
                id,
                row.version(),
                row.basic(),
                row.basicState(),
                row.basicOrigin(),
                row.extensions(),
                repository.experiences(id),
                documents.list(id));
    }

    public List<Proposal> proposals(long id, Long ignored, int size) {
        requireExists(id);
        if (size < 1 || size > 100) throw new CandidateFailure(400, "VALIDATION_ERROR");
        return repository.proposals(id, size);
    }

    public void requireExists(long id) {
        if (!repository.exists(id)) throw new CandidateFailure(404, "CANDIDATE_NOT_FOUND");
    }
}
