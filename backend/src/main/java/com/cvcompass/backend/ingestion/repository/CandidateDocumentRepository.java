package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.ingestion.dto.DocumentCandidate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                        "UPDATE \"INGESTION\".\"DOCUMENTS\" SET \"CANDIDATE_ID\"=? WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf' AND (\"CANDIDATE_ID\" IS NULL OR \"CANDIDATE_ID\"=?)",
                        candidateId,
                        documentId,
                        candidateId)
                == 1;
    }

    public List<DocumentCandidate> lockCandidate(long documentId) {
        return jdbc.query(
                "SELECT \"CANDIDATE_ID\" FROM \"INGESTION\".\"DOCUMENTS\" WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf' FOR UPDATE",
                (rs, n) -> new DocumentCandidate((Long) rs.getObject(1)),
                documentId);
    }

    public long createCandidate() {
        return jdbc.queryForObject(
                "INSERT INTO \"CANDIDATE\".\"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"",
                Long.class);
    }

    public void resolve(long id, long documentId) {
        jdbc.update(
                "UPDATE \"INGESTION\".\"DOCUMENTS\" SET \"CANDIDATE_ID\"=? WHERE \"ID\"=? AND \"CANDIDATE_ID\" IS NULL",
                id,
                documentId);
    }

    public boolean valid(long documentId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"INGESTION\".\"DOCUMENTS\" WHERE \"ID\"=? AND \"MIME_TYPE\"='application/pdf')",
                        Boolean.class,
                        documentId));
    }

    public List<CandidateDocuments.Document> list(long candidateId) {
        return jdbc.query(
                "SELECT \"ID\",\"ORIGINAL_FILENAME\",\"CREATED_AT\" FROM \"INGESTION\".\"DOCUMENTS\" WHERE \"CANDIDATE_ID\"=? ORDER BY \"ID\" DESC",
                (rs, n) ->
                        new CandidateDocuments.Document(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getTimestamp(3).toInstant().toString()),
                candidateId);
    }

    public Map<Long, String> firstFilenames(Collection<Long> candidateIds) {
        var result = new HashMap<Long, String>();
        // Page size is bounded by the list API (<= 100), so a placeholder list stays small.
        String placeholders =
                String.join(",", java.util.Collections.nCopies(candidateIds.size(), "?"));
        jdbc.query(
                "SELECT DISTINCT ON (\"CANDIDATE_ID\") \"CANDIDATE_ID\",\"ORIGINAL_FILENAME\" FROM \"INGESTION\".\"DOCUMENTS\" WHERE \"CANDIDATE_ID\" IN ("
                        + placeholders
                        + ") ORDER BY \"CANDIDATE_ID\",\"ID\"",
                rs -> {
                    result.put(rs.getLong(1), rs.getString(2));
                },
                candidateIds.toArray());
        return result;
    }

    public void lock(long documentId) {
        jdbc.queryForObject(
                "SELECT \"ID\" FROM \"INGESTION\".\"DOCUMENTS\" WHERE \"ID\"=? FOR UPDATE",
                Long.class,
                documentId);
    }
}
