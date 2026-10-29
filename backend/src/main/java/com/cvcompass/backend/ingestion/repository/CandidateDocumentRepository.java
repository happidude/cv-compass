package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.ingestion.dto.DocumentCandidate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CandidateDocumentRepository {
    private final JdbcTemplate jdbc;

    public CandidateDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean attach(long documentId, long candidateId) {
        return jdbc.update(
                        "UPDATE \"CV_DOCUMENTS\" SET \"CANDIDATE_ID\"=? WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf' AND (\"CANDIDATE_ID\" IS NULL OR \"CANDIDATE_ID\"=?)",
                        candidateId,
                        documentId,
                        candidateId)
                == 1;
    }

    public List<DocumentCandidate> lockCandidate(long documentId) {
        return jdbc.query(
                "SELECT \"CANDIDATE_ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf' FOR UPDATE",
                (rs, n) -> new DocumentCandidate((Long) rs.getObject(1)),
                documentId);
    }

    public long createCandidate() {
        return jdbc.queryForObject(
                "INSERT INTO \"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"", Long.class);
    }

    public void resolve(long id, long documentId) {
        jdbc.update(
                "UPDATE \"CV_DOCUMENTS\" SET \"CANDIDATE_ID\"=? WHERE \"ID\"=? AND \"CANDIDATE_ID\" IS NULL",
                id,
                documentId);
    }

    public boolean valid(long documentId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"CV_DOCUMENTS\" WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf')",
                        Boolean.class,
                        documentId));
    }

    public List<CandidateDocuments.Document> list(long candidateId) {
        return jdbc.query(
                "SELECT \"ID\",\"ORIGINAL_FILENAME\",\"CREATED_AT\" FROM \"CV_DOCUMENTS\" WHERE \"CANDIDATE_ID\"=? ORDER BY \"ID\" DESC",
                (rs, n) ->
                        new CandidateDocuments.Document(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getTimestamp(3).toInstant().toString()),
                candidateId);
    }

    public void lock(long documentId) {
        jdbc.queryForObject(
                "SELECT \"ID\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=? FOR UPDATE",
                Long.class,
                documentId);
    }
}
