package com.cvcompass.backend.catalog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaxonomyRepository {
    private final JdbcTemplate jdbc;

    public TaxonomyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void retireMissing(String kind, String[] codes) {
        jdbc.update(
                "UPDATE \"CATALOG\".\"TAXONOMY_CODES\" SET \"ACTIVE\"=false WHERE \"KIND\"=? AND NOT (\"CODE\"=ANY(?::text[]))",
                kind,
                codes);
    }

    public void upsert(String kind, String code, String label) {
        jdbc.update(
                """
                INSERT INTO "CATALOG"."TAXONOMY_CODES"
                  ("KIND","CODE","LABEL_VI","TAXONOMY_VERSION","ACTIVE")
                VALUES (?,?,?,'cv-taxonomy/0',true)
                ON CONFLICT ("KIND","CODE") DO UPDATE
                SET "LABEL_VI"=EXCLUDED."LABEL_VI", "TAXONOMY_VERSION"=EXCLUDED."TAXONOMY_VERSION", "ACTIVE"=true
                """,
                kind,
                code,
                label);
    }

    public boolean isActive(String kind, String code) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM \"CATALOG\".\"TAXONOMY_CODES\" WHERE \"KIND\"=? AND \"CODE\"=? AND \"ACTIVE\")",
                        Boolean.class,
                        kind,
                        code));
    }
}
