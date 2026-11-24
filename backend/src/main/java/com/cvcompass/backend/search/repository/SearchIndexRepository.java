package com.cvcompass.backend.search.repository;

import com.cvcompass.backend.search.dto.IndexSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchIndexRepository {
    private final JdbcTemplate jdbc;

    public SearchIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void delete(long candidateId) {
        jdbc.update(
                "DELETE FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?", candidateId);
    }

    public void upsert(IndexSnapshot value) {
        jdbc.update(
                """
                INSERT INTO "SEARCH"."CANDIDATE_INDEX" ("CANDIDATE_ID","SEARCH_TEXT","SEARCH_TSV",
                  "COMPANY_IDS","PARENT_COMPANY_IDS","TITLE_IDS","FUNCTION_CODES","SENIORITY_CODES",
                  "INDUSTRY_CODES","SCOPE_CODES","LOCATION_PROVINCE","TOTAL_EXPERIENCE_MONTHS",
                  "HAS_AI_NORMALIZATION","UPDATED_AT")
                VALUES (?,?,to_tsvector('simple',?),?::bigint[],?::bigint[],?::bigint[],?::text[],
                  ?::text[],?::text[],?::text[],?,?,?,clock_timestamp())
                ON CONFLICT ("CANDIDATE_ID") DO UPDATE SET
                  "SEARCH_TEXT"=EXCLUDED."SEARCH_TEXT","SEARCH_TSV"=EXCLUDED."SEARCH_TSV",
                  "COMPANY_IDS"=EXCLUDED."COMPANY_IDS","PARENT_COMPANY_IDS"=EXCLUDED."PARENT_COMPANY_IDS",
                  "TITLE_IDS"=EXCLUDED."TITLE_IDS","FUNCTION_CODES"=EXCLUDED."FUNCTION_CODES",
                  "SENIORITY_CODES"=EXCLUDED."SENIORITY_CODES","INDUSTRY_CODES"=EXCLUDED."INDUSTRY_CODES",
                  "SCOPE_CODES"=EXCLUDED."SCOPE_CODES","LOCATION_PROVINCE"=EXCLUDED."LOCATION_PROVINCE",
                  "TOTAL_EXPERIENCE_MONTHS"=EXCLUDED."TOTAL_EXPERIENCE_MONTHS",
                  "HAS_AI_NORMALIZATION"=EXCLUDED."HAS_AI_NORMALIZATION","UPDATED_AT"=EXCLUDED."UPDATED_AT"
                """,
                value.candidateId(),
                value.text(),
                value.text(),
                value.companyIds().toArray(Long[]::new),
                value.parentCompanyIds().toArray(Long[]::new),
                value.titleIds().toArray(Long[]::new),
                value.functionCodes().toArray(String[]::new),
                value.seniorityCodes().toArray(String[]::new),
                value.industryCodes().toArray(String[]::new),
                value.scopeCodes().toArray(String[]::new),
                value.province(),
                value.months(),
                value.aiNormalization());
    }
}
