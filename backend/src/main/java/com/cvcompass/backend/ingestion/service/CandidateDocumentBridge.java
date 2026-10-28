package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ingestion retains sole ownership of the legacy identity and document association. */
@Service
public class CandidateDocumentBridge implements CandidateDocuments {
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attach(long documentId, long candidateId) {
        return jdbc.update(
                        "UPDATE \"CV_DOCUMENTS\" SET \"CANDIDATE_ID\"=? WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf' AND (\"CANDIDATE_ID\" IS NULL OR \"CANDIDATE_ID\"=?)",
                        candidateId,
                        documentId,
                        candidateId)
                == 1;
    }

    private final JdbcTemplate jdbc;

    public CandidateDocumentBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long lockAndResolve(long documentId) {
        var rows =
                jdbc.queryForList(
                        "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf' FOR UPDATE",
                        documentId);
        if (rows.isEmpty()) throw new IllegalArgumentException("INVALID_DOCUMENT_SOURCE");
        Object existing = rows.getFirst().get("CANDIDATE_ID");
        if (existing != null) return ((Number) existing).longValue();
        long id =
                jdbc.queryForObject(
                        "INSERT INTO \"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"", Long.class);
        jdbc.update(
                "UPDATE \"CV_DOCUMENTS\" SET \"CANDIDATE_ID\"=? WHERE \"ID\"=? AND \"CANDIDATE_ID\" IS NULL",
                id,
                documentId);
        return id;
    }

    public boolean valid(long documentId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"CV_DOCUMENTS\" WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf')",
                        Boolean.class,
                        documentId));
    }

    public List<Document> list(long candidateId) {
        return jdbc.query(
                "SELECT \"ID\",\"ORIGINAL_FILENAME\",\"CREATED_AT\" FROM \"CV_DOCUMENTS\" WHERE \"CANDIDATE_ID\"=? ORDER BY \"ID\" DESC",
                (rs, n) ->
                        new Document(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getTimestamp(3).toInstant().toString()),
                candidateId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long documentId) {
        jdbc.queryForObject(
                "SELECT \"ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=? FOR UPDATE",
                Long.class,
                documentId);
    }
}
