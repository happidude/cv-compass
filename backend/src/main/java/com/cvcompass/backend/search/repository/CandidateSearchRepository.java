package com.cvcompass.backend.search.repository;

import com.cvcompass.backend.search.config.SearchProperties.Query;
import com.cvcompass.backend.search.dto.CandidateSearchPage.CompanySuggestion;
import com.cvcompass.backend.search.dto.CandidateSearchPage.TaxonomyOption;
import com.cvcompass.backend.search.dto.SearchCursor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CandidateSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CandidateSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<Long> resolve(String kind, String text, Query properties) {
        return jdbc.queryForList(
                """
                WITH exact AS (
                  SELECT e."ID",CASE WHEN e."NAME_FOLDED"=:text THEN 2 ELSE 1 END AS relevance
                  FROM "CATALOG"."ENTRIES" e
                  WHERE e."KIND"=:kind AND (e."NAME_FOLDED"=:text OR EXISTS (
                    SELECT 1 FROM "CATALOG"."ALIASES" a
                    WHERE a."KIND"=:kind AND a."STATE"='DONE' AND a."ENTRY_ID"=e."ID"
                      AND a."ALIAS_FOLDED"=:text))
                ), fuzzy AS (
                  SELECT e."ID",similarity(e."NAME_FOLDED",:text) AS relevance
                  FROM "CATALOG"."ENTRIES" e
                  WHERE e."KIND"=:kind AND NOT EXISTS (SELECT 1 FROM exact)
                    AND similarity(e."NAME_FOLDED",:text)>=:threshold
                  ORDER BY relevance DESC,e."ID" DESC LIMIT :limit
                )
                SELECT "ID" FROM (
                  SELECT "ID",relevance FROM exact UNION ALL SELECT "ID",relevance FROM fuzzy
                ) resolved ORDER BY relevance DESC,"ID" DESC
                """,
                Map.of(
                        "kind",
                        kind,
                        "text",
                        text,
                        "threshold",
                        properties.getTrigramThreshold(),
                        "limit",
                        properties.getResolveLimit()),
                Long.class);
    }

    public List<CompanySuggestion> companies(String text, Query properties) {
        return jdbc.query(
                """
                SELECT e."ID",e."NAME",p."NAME" AS parent_name FROM "CATALOG"."ENTRIES" e
                LEFT JOIN "CATALOG"."ENTRIES" p ON p."ID"=e."PARENT_ID"
                WHERE e."KIND"='COMPANY' AND (starts_with(e."NAME_FOLDED",:text)
                  OR similarity(e."NAME_FOLDED",:text)>=:threshold
                  OR EXISTS (SELECT 1 FROM "CATALOG"."ALIASES" a
                    WHERE a."KIND"='COMPANY' AND a."STATE"='DONE' AND a."ENTRY_ID"=e."ID"
                      AND (starts_with(a."ALIAS_FOLDED",:text)
                        OR similarity(a."ALIAS_FOLDED",:text)>=:threshold)))
                ORDER BY (e."NAME_FOLDED"=:text) DESC,
                  similarity(e."NAME_FOLDED",:text) DESC,e."NAME",e."ID" DESC LIMIT :limit
                """,
                Map.of(
                        "text",
                        text,
                        "threshold",
                        properties.getTrigramThreshold(),
                        "limit",
                        properties.getSuggestLimit()),
                (rs, row) ->
                        new CompanySuggestion(
                                rs.getLong("ID"),
                                rs.getString("NAME"),
                                rs.getString("parent_name")));
    }

    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String kind : List.of("FUNCTION", "SENIORITY", "SCOPE", "INDUSTRY")) {
            result.put(
                    kind,
                    jdbc.query(
                            """
                    SELECT "CODE","LABEL_VI" FROM "CATALOG"."TAXONOMY_CODES"
                    WHERE "KIND"=:kind AND "ACTIVE" ORDER BY "CODE"
                    """,
                            Map.of("kind", kind),
                            (rs, row) ->
                                    new TaxonomyOption(
                                            rs.getString("CODE"), rs.getString("LABEL_VI"))));
        }
        result.put(
                "provinces",
                jdbc.queryForList(
                        """
                SELECT DISTINCT "LOCATION_PROVINCE" FROM "SEARCH"."CANDIDATE_INDEX"
                WHERE "LOCATION_PROVINCE" IS NOT NULL ORDER BY "LOCATION_PROVINCE"
                """,
                        Map.of(),
                        String.class));
        return result;
    }

    public Map<String, List<String>> activeCodes(List<String> kinds) {
        if (kinds.isEmpty()) return Map.of();
        return jdbc.query(
                """
                SELECT "KIND","CODE" FROM "CATALOG"."TAXONOMY_CODES"
                WHERE "KIND" IN (:kinds) AND "ACTIVE"
                """,
                Map.of("kinds", kinds),
                rs -> {
                    Map<String, List<String>> result = new LinkedHashMap<>();
                    while (rs.next())
                        result.computeIfAbsent(rs.getString("KIND"), key -> new ArrayList<>())
                                .add(rs.getString("CODE"));
                    return result;
                });
    }

    public List<Row> search(Criteria criteria, Query properties) {
        // Transaction-local settings keep pooled connections and concurrent searches independent.
        jdbc.queryForObject(
                "SELECT set_config('pg_trgm.word_similarity_threshold',:threshold,true)",
                Map.of("threshold", properties.getKeywordTrigramThreshold().toPlainString()),
                String.class);
        var plan = prepare(criteria, properties);
        return jdbc.query(
                plan.sql(),
                plan.parameters(),
                (rs, row) -> {
                    var reasons = new ArrayList<String>();
                    for (String reason :
                            List.of(
                                    "COMPANY_DIRECT",
                                    "COMPANY_PARENT",
                                    "COMPANY_TEXT",
                                    "COMPANY_CURRENT",
                                    "TITLE_CATALOG",
                                    "TITLE_TEXT",
                                    "TITLE_CURRENT",
                                    "KEYWORD_TEXT",
                                    "KEYWORD_FUZZY")) {
                        if (rs.getBoolean(reason)) reasons.add(reason);
                    }
                    return new Row(
                            rs.getLong("CANDIDATE_ID"),
                            rs.getString("FULL_NAME"),
                            rs.getObject("CURRENT_COMPANY_ID", Long.class),
                            rs.getObject("CURRENT_TITLE_ID", Long.class),
                            rs.getString("LOCATION_PROVINCE"),
                            rs.getObject("TOTAL_EXPERIENCE_MONTHS", Integer.class),
                            rs.getBigDecimal("score"),
                            List.copyOf(reasons),
                            ids(rs, "COMPANY_IDS"),
                            ids(rs, "PARENT_COMPANY_IDS"));
                });
    }

    private static List<Long> ids(ResultSet result, String column) throws SQLException {
        var array = result.getArray(column);
        try {
            return List.of((Long[]) array.getArray());
        } finally {
            array.free();
        }
    }

    /** The same parameterized statement is exposed for integration EXPLAIN evidence. */
    public PreparedSearch prepare(Criteria c, Query properties) {
        var p =
                new MapSqlParameterSource()
                        .addValue("q", c.q())
                        .addValue("companyText", escapeLike(c.company()))
                        .addValue("titleText", escapeLike(c.title()))
                        .addValue("companies", c.companyIds().toArray(Long[]::new))
                        .addValue("titles", c.titleIds().toArray(Long[]::new))
                        .addValue("limit", c.limit() + 1);
        var predicates = new ArrayList<String>();
        if (!c.q().isEmpty())
            predicates.add(
                    "(i.\"SEARCH_TSV\" @@ plainto_tsquery('simple',:q) OR :q <% i.\"SEARCH_TEXT\")");
        if (c.companyPresent())
            predicates.add(
                    "(i.\"COMPANY_IDS\" && CAST(:companies AS bigint[]) OR i.\"PARENT_COMPANY_IDS\" && CAST(:companies AS bigint[]) OR (:companyText<>'' AND i.\"SEARCH_TEXT\" LIKE '%' || :companyText || '%' ESCAPE '\\'))");
        if (c.titlePresent())
            predicates.add(
                    "(i.\"TITLE_IDS\" && CAST(:titles AS bigint[]) OR (:titleText<>'' AND i.\"SEARCH_TEXT\" LIKE '%' || :titleText || '%' ESCAPE '\\'))");
        for (String field : List.of("FUNCTION", "SENIORITY", "SCOPE", "INDUSTRY")) {
            List<String> codes = c.codes().getOrDefault(field, List.of());
            if (!codes.isEmpty()) {
                predicates.add("i.\"" + field + "_CODES\" && CAST(:" + field + " AS text[])");
                p.addValue(field, codes.toArray(String[]::new));
            }
        }
        if (!c.provinces().isEmpty()) {
            predicates.add("i.\"LOCATION_PROVINCE\"=ANY(CAST(:provinces AS text[]))");
            p.addValue("provinces", c.provinces().toArray(String[]::new));
        }
        if (c.minYears() != null) {
            predicates.add("floor(i.\"TOTAL_EXPERIENCE_MONTHS\"::numeric/12)>=:minYears");
            p.addValue("minYears", c.minYears());
        }
        if (c.maxYears() != null) {
            predicates.add("floor(i.\"TOTAL_EXPERIENCE_MONTHS\"::numeric/12)<=:maxYears");
            p.addValue("maxYears", c.maxYears());
        }
        String rank =
                c.q().isEmpty()
                        ? "0::numeric"
                        : "ts_rank(i.\"SEARCH_TSV\",plainto_tsquery('simple',:q),32)::numeric";
        String fuzzy = c.q().isEmpty() ? "false" : ":q <% i.\"SEARCH_TEXT\"";
        String flags =
                """
                SELECT i.*,p."FULL_NAME",p."CURRENT_COMPANY_ID",p."CURRENT_TITLE_ID",
                  i."COMPANY_IDS" && CAST(:companies AS bigint[]) AS company_direct,
                  i."PARENT_COMPANY_IDS" && CAST(:companies AS bigint[]) AS company_parent,
                  (:companyText<>'' AND i."SEARCH_TEXT" LIKE '%' || :companyText || '%' ESCAPE '\\') AS company_text,
                  COALESCE(p."CURRENT_COMPANY_ID"=ANY(CAST(:companies AS bigint[])) OR EXISTS (
                    SELECT 1 FROM "CATALOG"."ENTRIES" e WHERE e."ID"=p."CURRENT_COMPANY_ID"
                      AND e."PARENT_ID"=ANY(CAST(:companies AS bigint[]))),false) AS company_current,
                  i."TITLE_IDS" && CAST(:titles AS bigint[]) AS title_catalog,
                  (:titleText<>'' AND i."SEARCH_TEXT" LIKE '%' || :titleText || '%' ESCAPE '\\') AS title_text,
                  COALESCE(p."CURRENT_TITLE_ID"=ANY(CAST(:titles AS bigint[])),false) AS title_current,
                """
                        + rank
                        + " AS text_rank,"
                        + fuzzy
                        + " AS keyword_fuzzy FROM \"SEARCH\".\"CANDIDATE_INDEX\" i JOIN \"CANDIDATE\".\"PROFILES\" p ON p.\"CANDIDATE_ID\"=i.\"CANDIDATE_ID\" WHERE "
                        + (predicates.isEmpty() ? "true" : String.join(" AND ", predicates));
        var w = properties.getWeights();
        p.addValue("wd", w.getCompanyDirect())
                .addValue("wp", w.getCompanyParent())
                .addValue("wt", w.getTitleCatalog())
                .addValue("wtb", w.getTitleCatalogBonus())
                .addValue("wct", w.getCompanyText())
                .addValue("wtt", w.getTitleText())
                .addValue("wcc", w.getCompanyCurrent())
                .addValue("wtc", w.getTitleCurrent())
                .addValue("wkt", w.getKeywordText())
                .addValue("wkf", w.getKeywordFuzzy());
        String after = "";
        if (c.cursor() != null) {
            after = "WHERE (score,\"CANDIDATE_ID\") < (:score,:id)";
            p.addValue("score", c.cursor().score()).addValue("id", c.cursor().id());
        }
        String sql =
                "WITH flags AS ("
                        + flags
                        + """
                ), ranked AS (
                  SELECT flags.*,text_rank>0 AS keyword_text,
                    round(CAST(CASE WHEN company_direct THEN :wd WHEN company_parent THEN :wp
                      WHEN title_catalog THEN :wt ELSE 0 END
                      + CASE WHEN title_catalog AND (company_direct OR company_parent) THEN :wtb ELSE 0 END
                      + CASE WHEN company_text THEN :wct ELSE 0 END
                      + CASE WHEN title_text THEN :wtt ELSE 0 END
                      + CASE WHEN company_current THEN :wcc ELSE 0 END
                      + CASE WHEN title_current THEN :wtc ELSE 0 END
                      + LEAST(1,text_rank)*:wkt
                      + CASE WHEN keyword_fuzzy THEN :wkf ELSE 0 END AS numeric),6) AS score
                  FROM flags)
                SELECT * FROM ranked
                """
                        + after
                        + " ORDER BY score DESC,\"CANDIDATE_ID\" DESC LIMIT :limit";
        return new PreparedSearch(sql, p);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record Criteria(
            String q,
            String company,
            String title,
            boolean companyPresent,
            boolean titlePresent,
            List<Long> companyIds,
            List<Long> titleIds,
            Map<String, List<String>> codes,
            List<String> provinces,
            Integer minYears,
            Integer maxYears,
            SearchCursor cursor,
            int limit) {}

    public record PreparedSearch(String sql, MapSqlParameterSource parameters) {}

    public record Row(
            long id,
            String fullName,
            Long companyId,
            Long titleId,
            String province,
            Integer months,
            BigDecimal score,
            List<String> reasons,
            List<Long> companyIds,
            List<Long> parentCompanyIds) {}
}
