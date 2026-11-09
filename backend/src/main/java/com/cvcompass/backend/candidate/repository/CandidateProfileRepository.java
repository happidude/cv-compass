package com.cvcompass.backend.candidate.repository;

import com.cvcompass.backend.candidate.dto.BasicValues;
import com.cvcompass.backend.candidate.dto.Experience;
import com.cvcompass.backend.candidate.dto.ExperienceReadRow;
import com.cvcompass.backend.candidate.dto.ExperienceValues;
import com.cvcompass.backend.candidate.dto.History;
import com.cvcompass.backend.candidate.dto.ProfileCore;
import com.cvcompass.backend.candidate.dto.Proposal;
import com.cvcompass.backend.candidate.dto.Summary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CandidateProfileRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CandidateProfileRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Summary> list(Long before, int size) {
        return jdbc.query(
                """
                SELECT "CANDIDATE_ID","VERSION","FULL_NAME","BASIC_STATE","CREATED_AT"
    FROM "CANDIDATE_PROFILES" WHERE (?::bigint IS NULL OR "CANDIDATE_ID"<?)
    ORDER BY "CANDIDATE_ID" DESC LIMIT ?
    """,
                (rs, n) ->
                        new Summary(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getTimestamp(5).toInstant().toString()),
                before,
                before,
                size + 1);
    }

    public List<ProfileCore> detail(long id) {
        return jdbc.query(
                """
   SELECT "VERSION","BASIC"::text,"BASIC_STATE","BASIC_ORIGIN","EXTENSIONS"::text
   FROM "CANDIDATE_PROFILES" WHERE "CANDIDATE_ID"=?
   """,
                (rs, n) ->
                        new ProfileCore(
                                rs.getLong(1),
                                parse(rs.getString(2)),
                                rs.getString(3),
                                rs.getString(4),
                                parse(rs.getString(5))),
                id);
    }

    public List<ExperienceReadRow> experiences(long id) {
        return jdbc.query(
                """
   SELECT "ID","STATE","ORIGIN","DOCUMENT_ID","RESULT_ID","DATA"::text,
          "START_DATE","START_PRECISION","END_DATE","END_PRECISION","ONGOING_AS_STATED"
   FROM "CANDIDATE_EXPERIENCES" WHERE "CANDIDATE_ID"=? AND NOT "DELETED" ORDER BY "ID"
   """,
                (rs, n) ->
                        new ExperienceReadRow(
                                new Experience(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3),
                                        (Long) rs.getObject(4),
                                        (Long) rs.getObject(5),
                                        parse(rs.getString(6))),
                                rs.getObject(7, LocalDate.class),
                                rs.getString(8),
                                rs.getObject(9, LocalDate.class),
                                rs.getString(10),
                                rs.getBoolean(11)),
                id);
    }

    public List<Proposal> proposals(long id, int size) {
        return jdbc.query(
                """
   SELECT "ID","DOCUMENT_ID","RESULT_ID","DATA"::text,"KIND" FROM "CANDIDATE_PROPOSALS"
   WHERE "CANDIDATE_ID"=? AND "STATUS"='OPEN' ORDER BY "RESULT_ID","ID" LIMIT ?
   """,
                (rs, n) ->
                        new Proposal(
                                rs.getObject(1, UUID.class),
                                rs.getLong(2),
                                rs.getLong(3),
                                parse(rs.getString(4)),
                                rs.getString(5)),
                id,
                size);
    }

    public List<History> history(long id, Long before, int size) {
        return jdbc.query(
                """
   SELECT "ID","VERSION","ACTOR","ACTION","BEFORE"::text,"AFTER"::text,"CREATED_AT"
   FROM "CANDIDATE_HISTORY" WHERE "CANDIDATE_ID"=? AND (?::bigint IS NULL OR "ID"<?)
   ORDER BY "ID" DESC LIMIT ?
   """,
                (rs, n) ->
                        new History(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4),
                                parse(rs.getString(5)),
                                parse(rs.getString(6)),
                                rs.getTimestamp(7).toInstant().toString()),
                id,
                before,
                before,
                size);
    }

    public boolean exists(long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"CANDIDATE_PROFILES\" WHERE \"CANDIDATE_ID\"=?)",
                        Boolean.class,
                        id));
    }

    public List<Long> lock(long id) {
        return jdbc.queryForList(
                "SELECT \"VERSION\" FROM \"CANDIDATE_PROFILES\" WHERE \"CANDIDATE_ID\"=? FOR UPDATE",
                Long.class,
                id);
    }

    public void revision(
            long id, long version, String actor, String action, String before, String after) {
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"VERSION\"=\"VERSION\"+1,\"UPDATED_AT\"=clock_timestamp() WHERE \"CANDIDATE_ID\"=?",
                id);
        jdbc.update(
                "INSERT INTO \"CANDIDATE_HISTORY\" (\"CANDIDATE_ID\",\"VERSION\",\"ACTOR\",\"ACTION\",\"BEFORE\",\"AFTER\") VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                id,
                version + 1,
                actor,
                action,
                before,
                after);
    }

    public void basic(long id, JsonNode value, BasicValues fields) {
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"BASIC\"=?::jsonb,\"FULL_NAME\"=?,\"EMAIL\"=?,\"PHONE\"=?,\"LOCATION\"=?,\"SUMMARY\"=?,\"BASIC_STATE\"='EDITED',\"BASIC_ORIGIN\"='USER' WHERE \"CANDIDATE_ID\"=?",
                value.toString(),
                fields.fullName(),
                fields.email(),
                fields.phone(),
                fields.location(),
                fields.summary(),
                id);
    }

    public void mappedBasic(
            long id,
            JsonNode value,
            BasicValues fields,
            String state,
            String origin,
            JsonNode extensions) {
        basic(id, value, fields);
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"BASIC_STATE\"=?,\"BASIC_ORIGIN\"=?,\"EXTENSIONS\"=?::jsonb WHERE \"CANDIDATE_ID\"=?",
                state,
                origin,
                extensions.toString(),
                id);
    }

    public void confirmBasic(long id) {
        jdbc.update(
                "UPDATE \"CANDIDATE_PROFILES\" SET \"BASIC_STATE\"='CONFIRMED' WHERE \"CANDIDATE_ID\"=?",
                id);
    }

    public void deleteExperience(UUID experienceId) {
        jdbc.update(
                "UPDATE \"CANDIDATE_EXPERIENCES\" SET \"DELETED\"=true,\"STATE\"='EDITED',\"ORIGIN\"='USER' WHERE \"ID\"=?",
                experienceId);
    }

    public void confirmExperience(UUID experienceId) {
        jdbc.update(
                "UPDATE \"CANDIDATE_EXPERIENCES\" SET \"STATE\"='CONFIRMED' WHERE \"ID\"=?",
                experienceId);
    }

    public List<Proposal> lockProposal(long id, UUID proposalId) {
        return jdbc.query(
                """
   SELECT "DOCUMENT_ID","RESULT_ID","DATA"::text,"KIND" FROM "CANDIDATE_PROPOSALS"
   WHERE "ID"=? AND "CANDIDATE_ID"=? AND "STATUS"='OPEN' FOR UPDATE
   """,
                (rs, n) ->
                        new Proposal(
                                proposalId,
                                rs.getLong(1),
                                rs.getLong(2),
                                parse(rs.getString(3)),
                                rs.getString(4)),
                proposalId,
                id);
    }

    public void decide(UUID proposalId, String decision, String actor) {
        jdbc.update(
                "UPDATE \"CANDIDATE_PROPOSALS\" SET \"STATUS\"=?,\"DECIDED_BY\"=?,\"DECIDED_AT\"=clock_timestamp() WHERE \"ID\"=?",
                "REJECT".equals(decision) ? "REJECTED" : "ACCEPTED",
                actor,
                proposalId);
    }

    public int experienceCount(long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM \"CANDIDATE_EXPERIENCES\" WHERE \"CANDIDATE_ID\"=?",
                Integer.class,
                id);
    }

    public void insertExperience(
            UUID key, long id, JsonNode data, String state, String origin, Long doc, Long result) {
        jdbc.update(
                "INSERT INTO \"CANDIDATE_EXPERIENCES\" (\"ID\",\"CANDIDATE_ID\",\"DATA\",\"STATE\",\"ORIGIN\",\"DOCUMENT_ID\",\"RESULT_ID\") VALUES (?,?,?::jsonb,?,?,?,?)",
                key,
                id,
                data.toString(),
                state,
                origin,
                doc,
                result);
    }

    public void updateExperience(
            UUID key,
            JsonNode data,
            String state,
            String origin,
            Long doc,
            Long result,
            ExperienceValues fields) {
        jdbc.update(
                """
   UPDATE "CANDIDATE_EXPERIENCES" SET "DATA"=?::jsonb,"STATE"=?,"ORIGIN"=?,"DOCUMENT_ID"=?,"RESULT_ID"=?,
   "COMPANY_RAW"=?,"COMPANY_NORMALIZED"=?,"TITLE_RAW"=?,"START_DATE"=?,"START_PRECISION"=?,"END_DATE"=?,"END_PRECISION"=?,
   "ONGOING_AS_STATED"=?,"FUNCTION_CODES"=?::jsonb,"INDUSTRY_CODES"=?::jsonb,"SENIORITY_CODE"=?,"DESCRIPTION_RAW"=? WHERE "ID"=?
   """,
                data.toString(),
                state,
                origin,
                doc,
                result,
                fields.companyRaw(),
                fields.companyNormalized(),
                fields.titleRaw(),
                fields.startDate(),
                fields.startPrecision(),
                fields.endDate(),
                fields.endPrecision(),
                fields.ongoingAsStated(),
                fields.functionCodes(),
                fields.industryCodes(),
                fields.seniorityCode(),
                fields.descriptionRaw(),
                key);
    }

    private JsonNode parse(String s) {
        return s == null ? null : json.readTree(s);
    }
}
