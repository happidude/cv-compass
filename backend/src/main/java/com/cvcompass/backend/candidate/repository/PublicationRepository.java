package com.cvcompass.backend.candidate.repository;

import com.cvcompass.backend.candidate.dto.PublicationCursor;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

@Repository
public class PublicationRepository {
    private final JdbcTemplate jdbc;

    public PublicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long resultId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"CANDIDATE_PUBLICATIONS\" WHERE \"RESULT_ID\"=?)",
                        Boolean.class,
                        resultId));
    }

    public boolean stale(CompletedMappings.Result result) {
        return Boolean.TRUE.equals(
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
    }

    public void ensureProfile(long id) {
        jdbc.update(
                "INSERT INTO \"CANDIDATE_PROFILES\" (\"CANDIDATE_ID\") VALUES (?) ON CONFLICT DO NOTHING",
                id);
    }

    public void propose(UUID key, long id, CompletedMappings.Result result, JsonNode experience) {
        jdbc.update(
                "INSERT INTO \"CANDIDATE_PROPOSALS\" (\"ID\",\"CANDIDATE_ID\",\"RESULT_ID\",\"DOCUMENT_ID\",\"DATA\") VALUES (?,?,?,?,?::jsonb)",
                key,
                id,
                result.id(),
                result.documentId(),
                experience.toString());
    }

    public void extensions(long id, String value) {
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"EXTENSIONS\"=?::jsonb WHERE \"CANDIDATE_ID\"=?",
                value,
                id);
    }

    public void ledger(CompletedMappings.Result result, Long candidate, String status) {
        jdbc.update(
                "INSERT INTO \"CANDIDATE_PUBLICATIONS\" (\"RESULT_ID\",\"CANDIDATE_ID\",\"DOCUMENT_ID\",\"EXTRACTION_ID\",\"CONFIG_ID\",\"STATUS\") VALUES (?,?,?,?,?,?)",
                result.id(),
                candidate,
                result.documentId(),
                result.extractionId(),
                result.configId(),
                status);
    }

    public PublicationCursor lockCursor() {
        return jdbc.queryForObject(
                "SELECT \"AFTER_ID\",\"UPPER_ID\" FROM \"CANDIDATE_PUBLICATION_SETTINGS\" WHERE \"ID\"=1 FOR UPDATE",
                (rs, n) -> new PublicationCursor(rs.getLong(1), (Long) rs.getObject(2)));
    }

    public void cursor(long after, Long upper) {
        jdbc.update(
                "UPDATE \"CANDIDATE_PUBLICATION_SETTINGS\" SET \"AFTER_ID\"=?,\"UPPER_ID\"=? WHERE \"ID\"=1",
                after,
                upper);
    }
}
