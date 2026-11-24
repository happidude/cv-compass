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
                "LOCK TABLE \"CATALOG\".\"ENTRIES\", \"CATALOG\".\"ALIASES\" IN SHARE ROW EXCLUSIVE MODE");
    }

    public CompanyKey ensureCompany(String name, String path) {
        return ensureCompany(name, path, null);
    }

    public CompanyKey ensureCompany(String name, String path, List<String> aliases) {
        var existing = resolveName("COMPANY", name, path);
        checkAliases("COMPANY", existing, aliases, path);
        if (existing.isPresent()) return new CompanyKey(existing.orElseThrow(), false);
        var inserted =
                jdbc.queryForList(
                        """
                INSERT INTO "CATALOG"."ENTRIES" ("KIND","NAME","NAME_FOLDED","ORIGIN") VALUES ('COMPANY',?,?,'SEED')
                ON CONFLICT ("KIND","NAME_FOLDED") DO NOTHING RETURNING "ID"
                """,
                        Long.class,
                        name,
                        Fold.fold(name));
        if (!inserted.isEmpty()) return new CompanyKey(inserted.getFirst(), true);
        return new CompanyKey(
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"KIND\"='COMPANY' AND \"NAME_FOLDED\"=?",
                        Long.class,
                        Fold.fold(name)),
                false);
    }

    public boolean updateCompany(long id, CatalogSeed.Company company, Long parentId) {
        return jdbc.update(
                        """
                UPDATE "CATALOG"."ENTRIES"
                SET "NAME"=?, "NAME_FOLDED"=?, "PARENT_ID"=?, "INDUSTRY_CODE"=?, "PROPOSED_LABEL"=NULL, "ORIGIN"='SEED', "UPDATED_AT"=now()
                WHERE "ID"=? AND "KIND"='COMPANY' AND "ORIGIN"<>'USER'
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
        var existing = resolveName("TITLE", title.name(), path);
        checkAliases("TITLE", existing, title.aliases(), path);
        if (existing.isPresent()) return new TitleKey(existing.orElseThrow(), false);
        long id =
                jdbc.queryForObject(
                        """
                INSERT INTO "CATALOG"."ENTRIES" ("KIND","NAME","NAME_FOLDED","FUNCTION_CODES","SENIORITY_CODE","SCOPE_CODE","ORIGIN")
                VALUES ('TITLE',?,?,?::text[],?,?,'SEED')
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
                UPDATE "CATALOG"."ENTRIES"
                SET "NAME"=?, "NAME_FOLDED"=?, "FUNCTION_CODES"=?::text[], "SENIORITY_CODE"=?, "SCOPE_CODE"=?,
                  "PROPOSED_LABEL"=NULL, "ORIGIN"='SEED', "UPDATED_AT"=now()
                WHERE "ID"=? AND "KIND"='TITLE' AND "ORIGIN"<>'USER' AND
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
        return upsertAlias("COMPANY", id, raw, path);
    }

    public List<Boolean> upsertTitleAlias(long id, String raw, String path) {
        return upsertAlias("TITLE", id, raw, path);
    }

    private List<Boolean> upsertAlias(String kind, long id, String raw, String path) {
        checkAliasOwner(kind, id, raw, path);
        return jdbc.queryForList(
                """
                INSERT INTO "CATALOG"."ALIASES" ("KIND","ALIAS_FOLDED","ENTRY_ID","ALIAS_RAW","ORIGIN") VALUES (?,?,?,?,'SEED')
                ON CONFLICT ("KIND","ALIAS_FOLDED") DO UPDATE SET "ALIAS_RAW"=EXCLUDED."ALIAS_RAW", "ORIGIN"='SEED'
                WHERE "ALIASES"."ENTRY_ID"=EXCLUDED."ENTRY_ID" AND "ALIASES"."ORIGIN"<>'USER' AND
                  ("ALIASES"."ENTRY_ID","ALIASES"."ALIAS_RAW","ALIASES"."ORIGIN")
                  IS DISTINCT FROM (EXCLUDED."ENTRY_ID",EXCLUDED."ALIAS_RAW",'SEED'::varchar)
                RETURNING (xmax=0)
                """,
                Boolean.class,
                kind,
                Fold.fold(raw),
                id,
                raw);
    }

    public boolean hasChildren(String name, String path) {
        var id = resolveName("COMPANY", name, path);
        return id.isPresent()
                && Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM \"CATALOG\".\"ENTRIES\" WHERE \"PARENT_ID\"=?)",
                                Boolean.class,
                                id.orElseThrow()));
    }

    public List<Long> cyclicCompanies(Long[] roots) {
        return jdbc.queryForList(
                """
                WITH RECURSIVE ancestors AS (
                  SELECT "ID" AS root_id, "ID", "PARENT_ID", ARRAY["ID"] AS visited, false AS cycle
                  FROM "CATALOG"."ENTRIES" WHERE "KIND"='COMPANY' AND "ID"=ANY(?::bigint[])
                  UNION ALL
                  SELECT a.root_id, p."ID", p."PARENT_ID", a.visited || p."ID", p."ID"=ANY(a.visited)
                  FROM ancestors a JOIN "CATALOG"."ENTRIES" p ON p."ID"=a."PARENT_ID"
                  WHERE NOT a.cycle
                ) SELECT DISTINCT root_id FROM ancestors WHERE cycle
                """,
                Long.class,
                (Object) roots);
    }

    private Optional<Long> resolveName(String kind, String name, String path) {
        var canonical =
                jdbc
                        .queryForList(
                                "SELECT \"ID\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"KIND\"=? AND \"NAME_FOLDED\"=?",
                                Long.class,
                                kind,
                                Fold.fold(name))
                        .stream()
                        .findFirst();
        var alias = aliasOwner(kind, name);
        if (canonical.isPresent() && alias.isPresent() && !canonical.equals(alias))
            throw aliasConflict(path);
        return canonical.isPresent() ? canonical : alias;
    }

    private Optional<Long> aliasOwner(String kind, String raw) {
        return jdbc
                .queryForList(
                        "SELECT \"ENTRY_ID\" FROM \"CATALOG\".\"ALIASES\" WHERE \"KIND\"=? AND \"ALIAS_FOLDED\"=?",
                        Long.class,
                        kind,
                        Fold.fold(raw))
                .stream()
                .findFirst();
    }

    private void checkAliasOwner(String kind, long id, String raw, String path) {
        var owner = aliasOwner(kind, raw);
        if (owner.isPresent() && owner.orElseThrow() != id) throw aliasConflict(path);
    }

    private void checkAliases(
            String kind, Optional<Long> id, List<String> aliases, String namePath) {
        if (aliases == null) return;
        for (int i = 0; i < aliases.size(); i++) {
            var owner = aliasOwner(kind, aliases.get(i));
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
