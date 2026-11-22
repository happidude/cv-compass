package com.cvcompass.backend.catalog.repository;

import com.cvcompass.backend.catalog.api.CatalogLookup;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class CatalogLookupRepository implements CatalogLookup {
    private final JdbcTemplate jdbc;

    public CatalogLookupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, Company> companies(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc
                .query(
                        """
                SELECT c."ID",c."NAME",c."PARENT_ID",p."NAME",c."INDUSTRY_CODE",c."ORIGIN"
                FROM "CATALOG"."COMPANIES" c LEFT JOIN "CATALOG"."COMPANIES" p ON p."ID"=c."PARENT_ID"
                WHERE c."ID"=ANY(?::bigint[])
                """,
                        (rs, row) ->
                                new Company(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getObject(3, Long.class),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6)),
                        (Object) ids.toArray(Long[]::new))
                .stream()
                .collect(Collectors.toUnmodifiableMap(Company::id, Function.identity()));
    }

    @Override
    public Map<Long, Title> titles(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc
                .query(
                        """
                SELECT "ID","NAME","FUNCTION_CODES","SENIORITY_CODE","SCOPE_CODE","ORIGIN"
                FROM "CATALOG"."TITLES" WHERE "ID"=ANY(?::bigint[])
                """,
                        (rs, row) -> {
                            var functions = rs.getArray(3);
                            try {
                                return new Title(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        List.copyOf(Arrays.asList((String[]) functions.getArray())),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6));
                            } finally {
                                functions.free();
                            }
                        },
                        (Object) ids.toArray(Long[]::new))
                .stream()
                .collect(Collectors.toUnmodifiableMap(Title::id, Function.identity()));
    }
}
