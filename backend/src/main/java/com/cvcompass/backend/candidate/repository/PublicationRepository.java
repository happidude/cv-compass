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
                        "SELECT EXISTS(SELECT 1 FROM \"CANDIDATE\".\"PUBLICATIONS\" WHERE \"RESULT_ID\"=?)",
                        Boolean.class,
                        resultId));
    }

    public boolean stale(CompletedMappings.Result result) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
   SELECT EXISTS(SELECT 1 FROM "CANDIDATE"."PUBLICATIONS" WHERE "DOCUMENT_ID"=? AND "STATUS" IN ('PUBLISHED','PROPOSED')
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
                "INSERT INTO \"CANDIDATE\".\"PROFILES\" (\"CANDIDATE_ID\") VALUES (?) ON CONFLICT DO NOTHING",
                id);
    }

    public void propose(UUID key, long id, CompletedMappings.Result result, JsonNode experience) {
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"PROPOSALS\" (\"ID\",\"CANDIDATE_ID\",\"RESULT_ID\",\"DOCUMENT_ID\",\"DATA\") VALUES (?,?,?,?,?::jsonb)",
                key,
                id,
                result.id(),
                result.documentId(),
                experience.toString());
    }

    public boolean proposePersonal(long id, CompletedMappings.Result result, JsonNode data) {
        var existing =
                jdbc.queryForList(
                        "SELECT \"DATA\"->'patch' AS patch FROM \"CANDIDATE\".\"PROPOSALS\" WHERE \"CANDIDATE_ID\"=? AND \"KIND\"='PERSONAL_INFO' AND \"STATUS\"='OPEN'",
                        String.class,
                        id);
        var json = new tools.jackson.databind.ObjectMapper();
        for (String value : existing) {
            var previous = json.readTree(value);
            var patch = data.path("patch");
            if (!new java.util.HashSet<>(previous.propertyNames())
                    .equals(new java.util.HashSet<>(patch.propertyNames()))) continue;
            boolean equal = true;
            for (String field : patch.propertyNames())
                equal &=
                        patch.path(field).isString()
                                && com.cvcompass.backend.candidate.mapping.PersonalInfoMapper.same(
                                        field, previous.path(field), patch.path(field).asString());
            if (equal) return false;
        }
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"PROPOSALS\" (\"ID\",\"CANDIDATE_ID\",\"RESULT_ID\",\"DOCUMENT_ID\",\"DATA\",\"KIND\") VALUES (?,?,?,?,?::jsonb,'PERSONAL_INFO')",
                UUID.randomUUID(),
                id,
                result.id(),
                result.documentId(),
                data.toString());
        return true;
    }

    public void extensions(long id, String value) {
        jdbc.update(
                "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"EXTENSIONS\"=\"EXTENSIONS\" || ?::jsonb WHERE \"CANDIDATE_ID\"=?",
                value,
                id);
    }

    public void ledger(CompletedMappings.Result result, Long candidate, String status) {
        ledger(result, candidate, status, null);
    }

    public void ledger(
            CompletedMappings.Result result, Long candidate, String status, String reason) {
        var reasons =
                java.util.Set.of(
                        "UNSUPPORTED_MAPPING_VERSION",
                        "UNSUPPORTED_MAPPING_QUALITY",
                        "MAPPING_SOURCE_MISMATCH",
                        "INVALID_MAPPING_ENVELOPE",
                        "NO_USABLE_FIELDS");
        if ("SKIPPED".equals(status) ? reason == null || !reasons.contains(reason) : reason != null)
            throw new IllegalArgumentException("Invalid publication reason");
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"PUBLICATIONS\" (\"RESULT_ID\",\"CANDIDATE_ID\",\"DOCUMENT_ID\",\"EXTRACTION_ID\",\"CONFIG_ID\",\"STATUS\",\"REASON_CODE\") VALUES (?,?,?,?,?,?,?)",
                result.id(),
                candidate,
                result.documentId(),
                result.extractionId(),
                result.configId(),
                status,
                reason);
    }

    public PublicationCursor lockCursor() {
        return jdbc.queryForObject(
                "SELECT \"AFTER_ID\",\"UPPER_ID\" FROM \"CANDIDATE\".\"PUBLICATION_SETTINGS\" WHERE \"ID\"=1 FOR UPDATE",
                (rs, n) -> new PublicationCursor(rs.getLong(1), (Long) rs.getObject(2)));
    }

    public void cursor(long after, Long upper) {
        jdbc.update(
                "UPDATE \"CANDIDATE\".\"PUBLICATION_SETTINGS\" SET \"AFTER_ID\"=?,\"UPPER_ID\"=? WHERE \"ID\"=1",
                after,
                upper);
    }
}
