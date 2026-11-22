package com.cvcompass.backend.catalog.repository;

import com.cvcompass.backend.catalog.Fold;
import com.cvcompass.backend.catalog.dto.CatalogSeed;
import com.cvcompass.backend.catalog.exception.CatalogFailure;
import com.cvcompass.backend.web.FieldViolation;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CompanyKey(long id, boolean created) {}

    public record TitleKey(long id, boolean created) {}

    public void lockImportTables() {
        // Serialize catalog writers while resolving aliases and validating the final parent graph.
        jdbc.execute(
                "LOCK TABLE \"CATALOG\".\"COMPANIES\", \"CATALOG\".\"TITLES\", \"CATALOG\".\"COMPANY_ALIASES\", \"CATALOG\".\"TITLE_ALIASES\" IN SHARE ROW EXCLUSIVE MODE");
    }

    public CompanyKey ensureCompany(String name, String path) {
        return ensureCompany(name, path, null);
    }

    public CompanyKey ensureCompany(String name, String path, List<String> aliases) {
        var existing = resolveName("COMPANIES", "COMPANY_ALIASES", "COMPANY_ID", name, path);
        checkAliases("COMPANY_ALIASES", "COMPANY_ID", existing, aliases, path);
        if (existing.isPresent()) return new CompanyKey(existing.orElseThrow(), false);
        var inserted =
                jdbc.queryForList(
                        """
                INSERT INTO "CATALOG"."COMPANIES" ("NAME","NAME_FOLDED","ORIGIN") VALUES (?,?,'SEED')
                ON CONFLICT ("NAME_FOLDED") DO NOTHING RETURNING "ID"
                """,
                        Long.class,
                        name,
                        Fold.fold(name));
        if (!inserted.isEmpty()) return new CompanyKey(inserted.getFirst(), true);
        return new CompanyKey(
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"=?",
                        Long.class,
                        Fold.fold(name)),
                false);
    }

    public boolean updateCompany(long id, CatalogSeed.Company company, Long parentId) {
        return jdbc.update(
                        """
                UPDATE "CATALOG"."COMPANIES"
                SET "NAME"=?, "NAME_FOLDED"=?, "PARENT_ID"=?, "INDUSTRY_CODE"=?, "PROPOSED_LABEL"=NULL, "ORIGIN"='SEED', "UPDATED_AT"=now()
                WHERE "ID"=? AND "ORIGIN"<>'USER'
                  AND ("NAME","NAME_FOLDED","PARENT_ID","INDUSTRY_CODE","PROPOSED_LABEL","ORIGIN")
                    IS DISTINCT FROM (?::text,?::text,?::bigint,?::varchar,NULL::text,'SEED'::varchar)
                """,
                        company.name(),
                        Fold.fold(company.name()),
                        parentId,
                        company.industry(),
                        id,
                        company.name(),
                        Fold.fold(company.name()),
                        parentId,
                        company.industry())
                > 0;
    }

    public TitleKey ensureTitle(CatalogSeed.Title title, String path) {
        var existing = resolveName("TITLES", "TITLE_ALIASES", "TITLE_ID", title.name(), path);
        checkAliases("TITLE_ALIASES", "TITLE_ID", existing, title.aliases(), path);
        if (existing.isPresent()) return new TitleKey(existing.orElseThrow(), false);
        long id =
                jdbc.queryForObject(
                        """
                INSERT INTO "CATALOG"."TITLES" ("NAME","NAME_FOLDED","FUNCTION_CODES","SENIORITY_CODE","SCOPE_CODE","ORIGIN")
                VALUES (?,?,?::text[],?,?,'SEED')
                RETURNING "ID"
                """,
                        Long.class,
                        title.name(),
                        Fold.fold(title.name()),
                        title.functions().toArray(String[]::new),
                        title.seniority(),
                        title.scope());
        return new TitleKey(id, true);
    }

    public boolean updateTitle(long id, CatalogSeed.Title title) {
        return jdbc.update(
                        """
                UPDATE "CATALOG"."TITLES"
                SET "NAME"=?, "NAME_FOLDED"=?, "FUNCTION_CODES"=?::text[], "SENIORITY_CODE"=?, "SCOPE_CODE"=?,
                  "PROPOSED_LABEL"=NULL, "ORIGIN"='SEED', "UPDATED_AT"=now()
                WHERE "ID"=? AND "ORIGIN"<>'USER' AND
                  ("NAME","NAME_FOLDED","FUNCTION_CODES","SENIORITY_CODE","SCOPE_CODE","PROPOSED_LABEL","ORIGIN")
                  IS DISTINCT FROM (?::text,?::text,?::text[],?::varchar,?::varchar,NULL::text,'SEED'::varchar)
                """,
                        title.name(),
                        Fold.fold(title.name()),
                        title.functions().toArray(String[]::new),
                        title.seniority(),
                        title.scope(),
                        id,
                        title.name(),
                        Fold.fold(title.name()),
                        title.functions().toArray(String[]::new),
                        title.seniority(),
                        title.scope())
                > 0;
    }

    public List<Boolean> upsertCompanyAlias(long id, String raw, String path) {
        checkAliasOwner("COMPANY_ALIASES", "COMPANY_ID", id, raw, path);
        return jdbc.queryForList(
                """
                INSERT INTO "CATALOG"."COMPANY_ALIASES" ("ALIAS_FOLDED","COMPANY_ID","ALIAS_RAW","ORIGIN") VALUES (?,?,?,'SEED')
                ON CONFLICT ("ALIAS_FOLDED") DO UPDATE SET "ALIAS_RAW"=EXCLUDED."ALIAS_RAW", "ORIGIN"='SEED'
                WHERE "COMPANY_ALIASES"."COMPANY_ID"=EXCLUDED."COMPANY_ID" AND "COMPANY_ALIASES"."ORIGIN"<>'USER' AND
                  ("COMPANY_ALIASES"."COMPANY_ID","COMPANY_ALIASES"."ALIAS_RAW","COMPANY_ALIASES"."ORIGIN")
                  IS DISTINCT FROM (EXCLUDED."COMPANY_ID",EXCLUDED."ALIAS_RAW",'SEED'::varchar)
                RETURNING (xmax=0)
                """,
                Boolean.class,
                Fold.fold(raw),
                id,
                raw);
    }

    public List<Boolean> upsertTitleAlias(long id, String raw, String path) {
        checkAliasOwner("TITLE_ALIASES", "TITLE_ID", id, raw, path);
        return jdbc.queryForList(
                """
                INSERT INTO "CATALOG"."TITLE_ALIASES" ("ALIAS_FOLDED","TITLE_ID","ALIAS_RAW","ORIGIN") VALUES (?,?,?,'SEED')
                ON CONFLICT ("ALIAS_FOLDED") DO UPDATE SET "ALIAS_RAW"=EXCLUDED."ALIAS_RAW", "ORIGIN"='SEED'
                WHERE "TITLE_ALIASES"."TITLE_ID"=EXCLUDED."TITLE_ID" AND "TITLE_ALIASES"."ORIGIN"<>'USER' AND
                  ("TITLE_ALIASES"."TITLE_ID","TITLE_ALIASES"."ALIAS_RAW","TITLE_ALIASES"."ORIGIN")
                  IS DISTINCT FROM (EXCLUDED."TITLE_ID",EXCLUDED."ALIAS_RAW",'SEED'::varchar)
                RETURNING (xmax=0)
                """,
                Boolean.class,
                Fold.fold(raw),
                id,
                raw);
    }

    public boolean hasChildren(String name, String path) {
        var id = resolveName("COMPANIES", "COMPANY_ALIASES", "COMPANY_ID", name, path);
        return id.isPresent()
                && Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM \"CATALOG\".\"COMPANIES\" WHERE \"PARENT_ID\"=?)",
                                Boolean.class,
                                id.orElseThrow()));
    }

    public List<Long> cyclicCompanies(Long[] roots) {
        return jdbc.queryForList(
                """
                WITH RECURSIVE ancestors AS (
                  SELECT "ID" AS root_id, "ID", "PARENT_ID", ARRAY["ID"] AS visited, false AS cycle
                  FROM "CATALOG"."COMPANIES" WHERE "ID"=ANY(?::bigint[])
                  UNION ALL
                  SELECT a.root_id, p."ID", p."PARENT_ID", a.visited || p."ID", p."ID"=ANY(a.visited)
                  FROM ancestors a JOIN "CATALOG"."COMPANIES" p ON p."ID"=a."PARENT_ID"
                  WHERE NOT a.cycle
                ) SELECT DISTINCT root_id FROM ancestors WHERE cycle
                """,
                Long.class,
                (Object) roots);
    }

    private Optional<Long> resolveName(
            String table, String aliases, String ownerColumn, String name, String path) {
        // Identifiers come only from the fixed catalog table names above; values remain bound.
        var canonical =
                jdbc
                        .queryForList(
                                "SELECT \"ID\" FROM \"CATALOG\".\""
                                        + table
                                        + "\" WHERE \"NAME_FOLDED\"=?",
                                Long.class,
                                Fold.fold(name))
                        .stream()
                        .findFirst();
        var alias = aliasOwner(aliases, ownerColumn, name);
        if (canonical.isPresent() && alias.isPresent() && !canonical.equals(alias))
            throw aliasConflict(path);
        return canonical.isPresent() ? canonical : alias;
    }

    private Optional<Long> aliasOwner(String table, String ownerColumn, String raw) {
        return jdbc
                .queryForList(
                        "SELECT \""
                                + ownerColumn
                                + "\" FROM \"CATALOG\".\""
                                + table
                                + "\" WHERE \"ALIAS_FOLDED\"=?",
                        Long.class,
                        Fold.fold(raw))
                .stream()
                .findFirst();
    }

    private void checkAliasOwner(
            String table, String ownerColumn, long id, String raw, String path) {
        var owner = aliasOwner(table, ownerColumn, raw);
        if (owner.isPresent() && owner.orElseThrow() != id) throw aliasConflict(path);
    }

    private void checkAliases(
            String table,
            String ownerColumn,
            Optional<Long> id,
            List<String> aliases,
            String namePath) {
        if (aliases == null) return;
        for (int i = 0; i < aliases.size(); i++) {
            var owner = aliasOwner(table, ownerColumn, aliases.get(i));
            if (owner.isPresent() && !owner.equals(id))
                throw aliasConflict(
                        namePath.substring(0, namePath.lastIndexOf('.')) + ".aliases[" + i + "]");
        }
    }

    private static CatalogFailure aliasConflict(String path) {
        return new CatalogFailure(
                "ALIAS_OWNED_BY_OTHER_ENTRY",
                List.of(new FieldViolation(path, "ALIAS_OWNED_BY_OTHER_ENTRY")));
    }
}
