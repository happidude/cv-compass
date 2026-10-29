package com.cvcompass.backend.ingestion.candidateapi;

import java.util.List;

/** Controlled identity/association boundary; writes require the caller's transaction. */
public interface CandidateDocuments {
    long lockAndResolve(long documentId);

    void lock(long documentId);

    boolean attach(long documentId, long candidateId);

    boolean valid(long documentId);

    List<Document> list(long candidateId);

    record Document(long id, String filename, String importedAt) {}
}
