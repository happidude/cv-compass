package com.cvcompass.backend.ingestion.candidateapi;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Controlled identity/association boundary; writes require the caller's transaction. */
public interface CandidateDocuments {
    long lockAndResolve(long documentId);

    void lock(long documentId);

    boolean attach(long documentId, long candidateId);

    boolean valid(long documentId);

    List<Document> list(long candidateId);

    /** First linked filename per candidate; a display fallback while no name is known. */
    Map<Long, String> firstFilenames(Collection<Long> candidateIds);

    record Document(long id, String filename, String importedAt) {}
}
