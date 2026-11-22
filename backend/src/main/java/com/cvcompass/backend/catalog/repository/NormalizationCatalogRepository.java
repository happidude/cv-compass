package com.cvcompass.backend.catalog.repository;

import com.cvcompass.backend.catalog.Fold;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NormalizationCatalogRepository {
    private final JdbcTemplate jdbc;

    public NormalizationCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasNonWhitespace(String value) {
        // PostgreSQL's whitespace class depends on the database locale, unlike String.isBlank().
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT ?::text ~ '[^[:space:]]'", Boolean.class, value));
    }

    public long withSavepoint(LongSupplier work) {
        // JDBC savepoints also work inside the existing JPA transaction without a nested JPA unit.
        return jdbc.execute(
                (ConnectionCallback<Long>)
                        connection -> {
                            var savepoint = connection.setSavepoint();
                            try {
                                return work.getAsLong();
                            } catch (RuntimeException failure) {
                                connection.rollback(savepoint);
                                throw failure;
                            } finally {
                                connection.releaseSavepoint(savepoint);
                            }
                        });
    }

    public Long alias(String kind, String folded) {
        return jdbc
                .queryForList(
                        "SELECT \"ENTRY_ID\" FROM \"CATALOG\".\"ALIASES\" WHERE \"KIND\"=? AND \"ALIAS_FOLDED\"=? AND \"STATE\"='DONE'",
                        Long.class,
                        kind,
                        folded)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public long company(String name, String industry, String proposedLabel) {
        jdbc.update(
                """
                INSERT INTO "CATALOG"."ENTRIES"
                  ("KIND","NAME","NAME_FOLDED","INDUSTRY_CODE","PROPOSED_LABEL","ORIGIN") VALUES ('COMPANY',?,?,?,?,'AI')
                ON CONFLICT ("KIND","NAME_FOLDED") DO NOTHING
                """,
                name.strip(),
                Fold.fold(name),
                industry,
                proposedLabel);
        return jdbc.queryForObject(
                "SELECT \"ID\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"KIND\"='COMPANY' AND \"NAME_FOLDED\"=?",
                Long.class,
                Fold.fold(name));
    }

    public boolean wouldCycle(String name, String parent) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                WITH RECURSIVE ancestors AS (
                  SELECT "ID","PARENT_ID","NAME_FOLDED" FROM "CATALOG"."ENTRIES" WHERE "KIND"='COMPANY' AND "NAME_FOLDED"=?
                  UNION
                  SELECT p."ID",p."PARENT_ID",p."NAME_FOLDED" FROM "CATALOG"."ENTRIES" p
                    JOIN ancestors a ON a."PARENT_ID"=p."ID"
                ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE "NAME_FOLDED"=?)
                """,
                        Boolean.class,
                        Fold.fold(parent),
                        Fold.fold(name)));
    }

    public boolean parent(long id, long parentId) {
        // Lock only this child and the proposed ancestry, in ID order. Under repeatable read a
        // concurrently changed ancestor causes a serialization failure instead of a parent cycle.
        jdbc.queryForList(
                """
                WITH RECURSIVE ancestors AS (
                  SELECT "ID","PARENT_ID" FROM "CATALOG"."ENTRIES" WHERE "KIND"='COMPANY' AND "ID"=?
                  UNION
                  SELECT p."ID",p."PARENT_ID" FROM "CATALOG"."ENTRIES" p
                    JOIN ancestors a ON a."PARENT_ID"=p."ID"
                ) SELECT c."ID" FROM "CATALOG"."ENTRIES" c
                WHERE c."ID"=? OR c."ID" IN (SELECT "ID" FROM ancestors)
                ORDER BY c."ID" FOR NO KEY UPDATE
                """,
                Long.class,
                parentId,
                id);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                WITH RECURSIVE ancestors AS (
                  SELECT "ID","PARENT_ID" FROM "CATALOG"."ENTRIES" WHERE "KIND"='COMPANY' AND "ID"=?
                  UNION
                  SELECT p."ID",p."PARENT_ID" FROM "CATALOG"."ENTRIES" p
                    JOIN ancestors a ON a."PARENT_ID"=p."ID"
                ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE "ID"=?)
                """,
                        Boolean.class,
                        parentId,
                        id))) return false;
        jdbc.update(
                """
                UPDATE "CATALOG"."ENTRIES" SET "PARENT_ID"=?,"UPDATED_AT"=now()
                WHERE "ID"=? AND "KIND"='COMPANY' AND "ORIGIN"='AI' AND "PARENT_ID" IS NULL AND "ID"<>?
                """,
                parentId,
                id,
                parentId);
        return true;
    }

    public long title(
            String name,
            List<String> functions,
            String seniority,
            String scope,
            String proposedLabel) {
        jdbc.update(
                """
                INSERT INTO "CATALOG"."ENTRIES"
                  ("KIND","NAME","NAME_FOLDED","FUNCTION_CODES","SENIORITY_CODE","SCOPE_CODE","PROPOSED_LABEL","ORIGIN")
                VALUES ('TITLE',?,?,?::text[],?,?,?,'AI') ON CONFLICT ("KIND","NAME_FOLDED") DO NOTHING
                """,
                name.strip(),
                Fold.fold(name),
                functions.toArray(String[]::new),
                seniority,
                scope,
                proposedLabel);
        return jdbc.queryForObject(
                "SELECT \"ID\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"KIND\"='TITLE' AND \"NAME_FOLDED\"=?",
                Long.class,
                Fold.fold(name));
    }
}
