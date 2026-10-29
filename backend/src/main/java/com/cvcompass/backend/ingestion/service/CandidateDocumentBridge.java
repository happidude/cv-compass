package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.ingestion.repository.CandidateDocumentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidateDocumentBridge implements CandidateDocuments {
    private final CandidateDocumentRepository repository;

    public CandidateDocumentBridge(CandidateDocumentRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attach(long documentId, long candidateId) {
        return repository.attach(documentId, candidateId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long lockAndResolve(long documentId) {
        var rows = repository.lockCandidate(documentId);
        if (rows.isEmpty()) throw new IllegalArgumentException("INVALID_DOCUMENT_SOURCE");
        Long existing = rows.getFirst().candidateId();
        if (existing != null) return existing;
        long id = repository.createCandidate();
        repository.resolve(id, documentId);
        return id;
    }

    public boolean valid(long documentId) {
        return repository.valid(documentId);
    }

    public List<Document> list(long candidateId) {
        return repository.list(candidateId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long documentId) {
        repository.lock(documentId);
    }
}
