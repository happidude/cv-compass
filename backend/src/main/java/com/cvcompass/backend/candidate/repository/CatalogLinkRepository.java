package com.cvcompass.backend.candidate.repository;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CatalogLinkRepository implements CatalogLinks {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CatalogLinkRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<ExperienceLink> experiences(long candidateId) {
        return jdbc.query(
                """
                SELECT "ID","CANDIDATE_ID","COMPANY_ID","TITLE_ID","SCOPE_CODE","NORMALIZATION_ORIGIN",
                  "COMPANY_RAW","COMPANY_NORMALIZED","TITLE_RAW","FUNCTION_CODES"::text,"INDUSTRY_CODES"::text,
                  "SENIORITY_CODE","START_DATE","START_PRECISION","END_DATE","END_PRECISION","ONGOING_AS_STATED",
                  "DOCUMENT_ID","DATA"::text
                FROM "CANDIDATE"."EXPERIENCES" WHERE "CANDIDATE_ID"=? AND NOT "DELETED" ORDER BY "ID"
                """,
                (rs, row) ->
                        new ExperienceLink(
                                rs.getObject(1, UUID.class),
                                rs.getLong(2),
                                rs.getObject(3, Long.class),
                                rs.getObject(4, Long.class),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getString(8),
                                rs.getString(9),
                                rs.getString(10),
                                rs.getString(11),
                                rs.getString(12),
                                rs.getObject(13, LocalDate.class),
                                rs.getString(14),
                                rs.getObject(15, LocalDate.class),
                                rs.getString(16),
                                rs.getBoolean(17),
                                rs.getObject(18, Long.class),
                                rs.getString(19)),
                candidateId);
    }

    @Override
    public Optional<ProfileLink> profile(long candidateId) {
        return jdbc
                .query(
                        """
                SELECT "CANDIDATE_ID","LOCATION_PROVINCE","TOTAL_EXPERIENCE_MONTHS","CURRENT_COMPANY_ID","CURRENT_TITLE_ID"
                FROM "CANDIDATE"."PROFILES" WHERE "CANDIDATE_ID"=?
                """,
                        (rs, row) ->
                                new ProfileLink(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getObject(3, Integer.class),
                                        rs.getObject(4, Long.class),
                                        rs.getObject(5, Long.class)),
                        candidateId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean normalize(long candidateId, UUID experienceId, Normalization value) {
        return jdbc.update(
                        """
                UPDATE "CANDIDATE"."EXPERIENCES"
                SET "COMPANY_ID"=?,"TITLE_ID"=?,"SCOPE_CODE"=?,"NORMALIZATION_ORIGIN"=?,
                  "COMPANY_NORMALIZED"=?,"FUNCTION_CODES"=?::jsonb,"INDUSTRY_CODES"=?::jsonb,"SENIORITY_CODE"=?
                WHERE "CANDIDATE_ID"=? AND "ID"=? AND NOT "DELETED"
                """,
                        value.companyId(),
                        value.titleId(),
                        value.scopeCode(),
                        value.origin(),
                        value.companyNormalized(),
                        json.writeValueAsString(value.functionCodes()),
                        json.writeValueAsString(value.industryCodes()),
                        value.seniorityCode(),
                        candidateId,
                        experienceId)
                > 0;
    }
}
