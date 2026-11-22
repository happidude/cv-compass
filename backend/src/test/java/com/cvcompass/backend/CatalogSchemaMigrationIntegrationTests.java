package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.candidate.repository.CatalogLinkRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class CatalogSchemaMigrationIntegrationTests {
    @Test
    void v16PreservesV14ProfilesAndExperiencesAndAddsNullableLinks() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var source = source(database);
            Flyway.configure().dataSource(source).target("14").load().migrate();
            var jdbc = new JdbcTemplate(source);
            long candidate = candidate(jdbc);
            UUID experience = experience(jdbc, candidate);
            String profileBefore =
                    jdbc.queryForObject(
                            "SELECT to_jsonb(p)::text FROM \"CANDIDATE\".\"PROFILES\" p WHERE \"CANDIDATE_ID\"=?",
                            String.class,
                            candidate);
            String experienceBefore =
                    jdbc.queryForObject(
                            "SELECT to_jsonb(e)::text FROM \"CANDIDATE\".\"EXPERIENCES\" e WHERE \"ID\"=?",
                            String.class,
                            experience);
            var flyway = Flyway.configure().dataSource(source).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("16");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT (to_jsonb(p)-ARRAY['LOCATION_PROVINCE','TOTAL_EXPERIENCE_MONTHS','CURRENT_COMPANY_ID','CURRENT_TITLE_ID','CURRENT_COMPANY_KIND','CURRENT_TITLE_KIND'])::text FROM \"CANDIDATE\".\"PROFILES\" p WHERE \"CANDIDATE_ID\"=?",
                                    String.class,
                                    candidate))
                    .isEqualTo(profileBefore);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT (to_jsonb(e)-ARRAY['COMPANY_ID','TITLE_ID','COMPANY_KIND','TITLE_KIND','SCOPE_CODE','NORMALIZATION_ORIGIN'])::text FROM \"CANDIDATE\".\"EXPERIENCES\" e WHERE \"ID\"=?",
                                    String.class,
                                    experience))
                    .isEqualTo(experienceBefore);
            var links = new CatalogLinkRepository(jdbc, new ObjectMapper());
            var profile = links.profile(candidate).orElseThrow();
            assertThat(profile.locationProvince()).isNull();
            assertThat(profile.totalExperienceMonths()).isNull();
            assertThat(profile.currentCompanyId()).isNull();
            assertThat(profile.currentTitleId()).isNull();
            var row = links.experiences(candidate).getFirst();
            assertThat(row.companyId()).isNull();
            assertThat(row.titleId()).isNull();
            assertThat(row.scopeCode()).isNull();
            assertThat(row.normalizationOrigin()).isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"SEARCH\".\"CANDIDATE_INDEX\"",
                                    Integer.class))
                    .isZero();
        }
    }

    @Test
    void constraintsCommentsExtensionsAndSearchIndexesMatchTheApprovedSchema() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var source = source(database);
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT tablename FROM pg_tables WHERE schemaname='CATALOG' ORDER BY tablename",
                                    String.class))
                    .containsExactly("ALIASES", "ENTRIES", "TAXONOMY_CODES");
            for (String table : List.of("TAXONOMY_CODES", "ENTRIES", "ALIASES"))
                comments(jdbc, "CATALOG", table);
            comments(jdbc, "SEARCH", "CANDIDATE_INDEX");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_indexes WHERE schemaname='CATALOG' AND tablename='NORMALIZATION_TASKS' AND indexdef LIKE '%(\"STATE\", \"NEXT_ATTEMPT_AT\")%'",
                                    Integer.class))
                    .isEqualTo(1);
            jdbc.update(
                    "INSERT INTO \"CATALOG\".\"NORMALIZATION_TASKS\" (\"KIND\",\"ALIAS_FOLDED\",\"ALIAS_RAW\") VALUES ('COMPANY','synthetic queue','Synthetic Queue'),('TITLE','synthetic queue','Synthetic Queue')");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"CATALOG\".\"NORMALIZATION_TASKS\" WHERE \"STATE\"='PENDING' AND \"ATTEMPTS\"=0 AND \"MAX_ATTEMPTS\"=3 AND \"TOKEN\" IS NULL AND \"LEASE_EXPIRES_AT\" IS NULL AND \"NEXT_ATTEMPT_AT\" IS NOT NULL AND \"CREATED_AT\" IS NOT NULL AND \"COMPLETED_AT\" IS NULL",
                                    Integer.class))
                    .isEqualTo(2);
            for (String values :
                    List.of(
                            "'COMPANY','synthetic queue','duplicate'",
                            "'INVALID','synthetic invalid','Synthetic Invalid'")) {
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "INSERT INTO \"CATALOG\".\"NORMALIZATION_TASKS\" (\"KIND\",\"ALIAS_FOLDED\",\"ALIAS_RAW\") VALUES ("
                                                        + values
                                                        + ")"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CATALOG\".\"NORMALIZATION_TASKS\" SET \"STATE\"='INVALID'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CATALOG\".\"NORMALIZATION_TASKS\" SET \"CONTEXT_COMPANY\"='Synthetic Context' WHERE \"KIND\"='COMPANY'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForList(
                                    """
                    SELECT a.attname FROM pg_attribute a
                    WHERE a.attrelid IN ('"CANDIDATE"."PROFILES"'::regclass,'"CANDIDATE"."EXPERIENCES"'::regclass)
                      AND a.attname IN ('COMPANY_ID','TITLE_ID','COMPANY_KIND','TITLE_KIND','SCOPE_CODE','NORMALIZATION_ORIGIN','LOCATION_PROVINCE','TOTAL_EXPERIENCE_MONTHS','CURRENT_COMPANY_ID','CURRENT_TITLE_ID','CURRENT_COMPANY_KIND','CURRENT_TITLE_KIND')
                      AND NULLIF(btrim(col_description(a.attrelid,a.attnum)),'') IS NULL
                    """,
                                    String.class))
                    .isEmpty();
            assertThat(
                            jdbc.queryForList(
                                    "SELECT e.extname FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE n.nspname='public' AND e.extname IN ('unaccent','pg_trgm') ORDER BY e.extname",
                                    String.class))
                    .containsExactly("pg_trgm", "unaccent");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_indexes WHERE schemaname='SEARCH' AND tablename='CANDIDATE_INDEX' AND indexdef LIKE '%USING gin%'",
                                    Integer.class))
                    .isEqualTo(9);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='SEARCH' AND c.relname='IDX_CANDIDATE_INDEX_SEARCH_TEXT'",
                                    String.class))
                    .contains("gin_trgm_ops");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"INDUSTRY_CODE\",\"ORIGIN\") VALUES ('COMPANY','Aster Constraint','aster constraint','OTHER','SEED')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"INDUSTRY_CODE\",\"PROPOSED_LABEL\",\"ORIGIN\") VALUES ('COMPANY','Aster Constraint','aster constraint','OTHER','   ','SEED')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES ('COMPANY',E' \\t\\n','blank','SEED')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            for (String functions : List.of("{OTHER}", "{IT,SALES,HR}", "{}"))
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"ORIGIN\") VALUES ('TITLE','Orbit Constraint','orbit constraint',?::text[],'STAFF','SEED')",
                                                functions))
                        .isInstanceOf(DataIntegrityViolationException.class);
            long company =
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"INDUSTRY_CODE\",\"PROPOSED_LABEL\",\"ORIGIN\") VALUES ('COMPANY','Aster Constraint','aster constraint','OTHER','Nhãn tổng hợp','SEED') RETURNING \"ID\"",
                            Long.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CATALOG\".\"ENTRIES\" SET \"PARENT_ID\"=\"ID\" WHERE \"KIND\"='COMPANY' AND \"ID\"=?",
                                            company))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CATALOG\".\"ENTRIES\" SET \"PARENT_ID\"=? WHERE \"KIND\"='COMPANY' AND \"ID\"=?",
                                            Long.MAX_VALUE,
                                            company))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(
                    "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"PROPOSED_LABEL\",\"ORIGIN\") VALUES ('TITLE','Orbit Constraint','orbit constraint','{OTHER}','STAFF','Nhãn tổng hợp','SEED')");
            long candidate = candidate(jdbc);
            UUID experience = experience(jdbc, candidate);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"TOTAL_EXPERIENCE_MONTHS\"=-1 WHERE \"CANDIDATE_ID\"=?",
                                            candidate))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CANDIDATE\".\"EXPERIENCES\" SET \"NORMALIZATION_ORIGIN\"='SEED' WHERE \"ID\"=?",
                                            experience))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CANDIDATE\".\"EXPERIENCES\" SET \"COMPANY_ID\"=? WHERE \"ID\"=?",
                                            Long.MAX_VALUE,
                                            experience))
                    .isInstanceOf(DataIntegrityViolationException.class);
            long indexed =
                    jdbc.queryForObject(
                            "INSERT INTO \"CANDIDATE\".\"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"",
                            Long.class);
            jdbc.update(
                    "INSERT INTO \"SEARCH\".\"CANDIDATE_INDEX\" (\"CANDIDATE_ID\",\"SEARCH_TEXT\",\"SEARCH_TSV\",\"UPDATED_AT\") VALUES (?,'synthetic','synthetic'::tsvector,now())",
                    indexed);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT cardinality(\"COMPANY_IDS\")+cardinality(\"PARENT_COMPANY_IDS\")+cardinality(\"TITLE_IDS\")+cardinality(\"FUNCTION_CODES\")+cardinality(\"SENIORITY_CODES\")+cardinality(\"INDUSTRY_CODES\")+cardinality(\"SCOPE_CODES\") FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                                    Integer.class,
                                    indexed))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"HAS_AI_NORMALIZATION\" FROM \"SEARCH\".\"CANDIDATE_INDEX\" WHERE \"CANDIDATE_ID\"=?",
                                    Boolean.class,
                                    indexed))
                    .isFalse();
            jdbc.update("DELETE FROM \"CANDIDATE\".\"CANDIDATES\" WHERE \"ID\"=?", indexed);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM \"SEARCH\".\"CANDIDATE_INDEX\"",
                                    Integer.class))
                    .isZero();
        }
    }

    @Test
    void
            candidateLinksMapProfileFieldsAndNormalizeOnlyMatchingLiveExperienceWithoutChangingSources()
                    throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var source = source(database);
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            var json = new ObjectMapper();
            var links = new CatalogLinkRepository(jdbc, json);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            long candidate = candidate(jdbc);
            UUID experience = experience(jdbc, candidate);
            UUID deleted = experience(jdbc, candidate);
            jdbc.update(
                    "UPDATE \"CANDIDATE\".\"EXPERIENCES\" SET \"DELETED\"=true WHERE \"ID\"=?",
                    deleted);
            long company =
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES ('COMPANY','Aster Links','aster links','SEED') RETURNING \"ID\"",
                            Long.class);
            long title =
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"ORIGIN\") VALUES ('TITLE','Orbit Links','orbit links','{IT}','MANAGER','SEED') RETURNING \"ID\"",
                            Long.class);
            jdbc.update(
                    "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"LOCATION_PROVINCE\"='Hà Nội',\"TOTAL_EXPERIENCE_MONTHS\"=24,\"CURRENT_COMPANY_ID\"=?,\"CURRENT_TITLE_ID\"=? WHERE \"CANDIDATE_ID\"=?",
                    company,
                    title,
                    candidate);
            var expected =
                    new CatalogLinks.Normalization(
                            company,
                            title,
                            "COMPANY",
                            "AI",
                            "Aster Links",
                            List.of("IT"),
                            List.of("RETAIL"),
                            "MANAGER");
            tx.executeWithoutResult(
                    status -> {
                        assertThat(links.normalize(candidate + 1, experience, expected)).isFalse();
                        assertThat(links.normalize(candidate, deleted, expected)).isFalse();
                        assertThat(links.normalize(candidate, experience, expected)).isTrue();
                    });
            var row = links.experiences(candidate).getFirst();
            assertThat(links.experiences(candidate)).hasSize(1);
            assertThat(row.companyId()).isEqualTo(company);
            assertThat(row.titleId()).isEqualTo(title);
            assertThat(row.scopeCode()).isEqualTo("COMPANY");
            assertThat(row.normalizationOrigin()).isEqualTo("AI");
            assertThat(row.companyNormalized()).isEqualTo("Aster Links");
            assertThat(json.readTree(row.functionCodes())).isEqualTo(json.readTree("[\"IT\"]"));
            assertThat(json.readTree(row.industryCodes())).isEqualTo(json.readTree("[\"RETAIL\"]"));
            assertThat(row.seniorityCode()).isEqualTo("MANAGER");
            assertThat(row.companyRaw()).isEqualTo("Aster Original");
            assertThat(row.titleRaw()).isEqualTo("Orbit Original");
            assertThat(json.readTree(row.data())).isEqualTo(json.readTree("{\"synthetic\":true}"));
            assertThat(row.startDate()).isEqualTo(java.time.LocalDate.of(2020, 1, 1));
            assertThat(links.profile(candidate).orElseThrow())
                    .isEqualTo(
                            new CatalogLinks.ProfileLink(candidate, "Hà Nội", 24, company, title));
            assertThat(links.profile(Long.MAX_VALUE)).isEmpty();
            tx.executeWithoutResult(
                    status -> {
                        links.normalize(
                                candidate,
                                experience,
                                new CatalogLinks.Normalization(
                                        null,
                                        null,
                                        null,
                                        "USER",
                                        "Synthetic rollback",
                                        List.of(),
                                        List.of(),
                                        null));
                        status.setRollbackOnly();
                    });
            assertThat(links.experiences(candidate).getFirst()).isEqualTo(row);
        }
    }

    @Test
    void compositeForeignKeysEnforceKindsForParentsAliasesAndCandidateLinks() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var source = source(database);
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            long company =
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES ('COMPANY','Synthetic Shared','synthetic shared','SEED') RETURNING \"ID\"",
                            Long.class);
            long title =
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"ORIGIN\") VALUES ('TITLE','Synthetic Shared','synthetic shared','{IT}','STAFF','SEED') RETURNING \"ID\"",
                            Long.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE \"CATALOG\".\"ENTRIES\" SET \"PARENT_ID\"=? WHERE \"ID\"=?",
                                            title,
                                            company))
                    .isInstanceOf(DataIntegrityViolationException.class);
            for (var mismatch : List.of(Map.entry("COMPANY", title), Map.entry("TITLE", company))) {
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "INSERT INTO \"CATALOG\".\"ALIASES\" (\"KIND\",\"ALIAS_FOLDED\",\"ENTRY_ID\",\"ALIAS_RAW\",\"ORIGIN\") VALUES (?,'synthetic shared',?,'Synthetic Shared','SEED')",
                                                mismatch.getKey(),
                                                mismatch.getValue()))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            for (var match : List.of(Map.entry("COMPANY", company), Map.entry("TITLE", title))) {
                jdbc.update(
                        "INSERT INTO \"CATALOG\".\"ALIASES\" (\"KIND\",\"ALIAS_FOLDED\",\"ENTRY_ID\",\"ALIAS_RAW\",\"ORIGIN\") VALUES (?,'synthetic shared',?,'Synthetic Shared','SEED')",
                        match.getKey(),
                        match.getValue());
            }
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO \"CATALOG\".\"ALIASES\" (\"KIND\",\"ALIAS_FOLDED\",\"ENTRY_ID\",\"ALIAS_RAW\",\"ORIGIN\") VALUES ('COMPANY','synthetic shared',?,'Synthetic Shared','SEED')",
                                            company))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES ('COMPANY','Synthetic Duplicate','synthetic shared','SEED')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            long candidate = candidate(jdbc);
            UUID experience = experience(jdbc, candidate);
            for (var mismatch :
                    List.of(Map.entry("COMPANY_ID", title), Map.entry("TITLE_ID", company))) {
                // Column names are fixed test cases; all data values remain bound.
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "UPDATE \"CANDIDATE\".\"EXPERIENCES\" SET \""
                                                        + mismatch.getKey()
                                                        + "\"=? WHERE \"ID\"=?",
                                                mismatch.getValue(),
                                                experience))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"CURRENT_"
                                                        + mismatch.getKey()
                                                        + "\"=? WHERE \"CANDIDATE_ID\"=?",
                                                mismatch.getValue(),
                                                candidate))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            jdbc.update(
                    "UPDATE \"CANDIDATE\".\"EXPERIENCES\" SET \"COMPANY_ID\"=?,\"TITLE_ID\"=? WHERE \"ID\"=?",
                    company,
                    title,
                    experience);
            jdbc.update(
                    "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"CURRENT_COMPANY_ID\"=?,\"CURRENT_TITLE_ID\"=? WHERE \"CANDIDATE_ID\"=?",
                    company,
                    title,
                    candidate);
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT \"COMPANY_KIND\",\"TITLE_KIND\" FROM \"CANDIDATE\".\"EXPERIENCES\" WHERE \"ID\"=?",
                                    experience))
                    .isEqualTo(Map.of("COMPANY_KIND", "COMPANY", "TITLE_KIND", "TITLE"));
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT \"CURRENT_COMPANY_KIND\",\"CURRENT_TITLE_KIND\" FROM \"CANDIDATE\".\"PROFILES\" WHERE \"CANDIDATE_ID\"=?",
                                    candidate))
                    .isEqualTo(
                            Map.of(
                                    "CURRENT_COMPANY_KIND",
                                    "COMPANY",
                                    "CURRENT_TITLE_KIND",
                                    "TITLE"));
            jdbc.update(
                    "UPDATE \"CANDIDATE\".\"EXPERIENCES\" SET \"COMPANY_ID\"=NULL,\"TITLE_ID\"=NULL WHERE \"ID\"=?",
                    experience);
            jdbc.update(
                    "UPDATE \"CANDIDATE\".\"PROFILES\" SET \"CURRENT_COMPANY_ID\"=NULL,\"CURRENT_TITLE_ID\"=NULL WHERE \"CANDIDATE_ID\"=?",
                    candidate);
            assertThat(
                            jdbc.queryForMap(
                                            "SELECT \"COMPANY_KIND\",\"TITLE_KIND\" FROM \"CANDIDATE\".\"EXPERIENCES\" WHERE \"ID\"=?",
                                            experience)
                                    .values())
                    .containsOnlyNulls();
            jdbc.update("DELETE FROM \"CATALOG\".\"ENTRIES\" WHERE \"ID\"=?", company);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT \"KIND\" FROM \"CATALOG\".\"ALIASES\"", String.class))
                    .containsExactly("TITLE");
        }
    }

    @Test
    void perKindChecksRejectIncompatibleFieldsAndRequireTitleClassification() throws Exception {
        try (var database = new IsolatedPostgresSchema()) {
            var source = source(database);
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            long company =
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES ('COMPANY','Synthetic Company','synthetic company','SEED') RETURNING \"ID\"",
                            Long.class);
            long title =
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"ORIGIN\") VALUES ('TITLE','Synthetic Title','synthetic title','{IT}','STAFF','SEED') RETURNING \"ID\"",
                            Long.class);
            for (String assignment :
                    List.of(
                            "\"FUNCTION_CODES\"='{IT}'",
                            "\"SENIORITY_CODE\"='STAFF'",
                            "\"SCOPE_CODE\"='COMPANY'",
                            "\"KIND\"='OTHER'")) {
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "UPDATE \"CATALOG\".\"ENTRIES\" SET "
                                                        + assignment
                                                        + " WHERE \"ID\"=?",
                                                company))
                        .as(assignment)
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String assignment :
                    List.of(
                            "\"PARENT_ID\"=" + company,
                            "\"INDUSTRY_CODE\"='RETAIL'",
                            "\"FUNCTION_CODES\"=NULL",
                            "\"FUNCTION_CODES\"='{}'",
                            "\"FUNCTION_CODES\"='{IT,SALES,HR}'",
                            "\"SENIORITY_CODE\"=NULL",
                            "\"FUNCTION_CODES\"='{OTHER}'",
                            "\"FUNCTION_CODES\"='{OTHER}',\"PROPOSED_LABEL\"='   '")) {
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "UPDATE \"CATALOG\".\"ENTRIES\" SET "
                                                        + assignment
                                                        + " WHERE \"ID\"=?",
                                                title))
                        .as(assignment)
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            jdbc.update(
                    "UPDATE \"CATALOG\".\"ENTRIES\" SET \"FUNCTION_CODES\"='{IT,SALES}' WHERE \"ID\"=?",
                    title);
            jdbc.update(
                    "UPDATE \"CATALOG\".\"ENTRIES\" SET \"PARENT_ID\"=? WHERE \"ID\"=?",
                    company,
                    jdbc.queryForObject(
                            "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES ('COMPANY','Synthetic Child','synthetic child','SEED') RETURNING \"ID\"",
                            Long.class));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT \"PARENT_KIND\" FROM \"CATALOG\".\"ENTRIES\" WHERE \"NAME_FOLDED\"='synthetic child'",
                                    String.class))
                    .isEqualTo("COMPANY");
        }
    }

    private static void comments(JdbcTemplate jdbc, String schema, String table) {
        String qualified = "\"" + schema + "\".\"" + table + "\"";
        assertThat(
                        jdbc.queryForObject(
                                "SELECT obj_description(?::regclass,'pg_class')",
                                String.class,
                                qualified))
                .isNotBlank();
        assertThat(
                        jdbc.queryForList(
                                "SELECT attname FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped AND NULLIF(btrim(col_description(attrelid,attnum)),'') IS NULL",
                                String.class,
                                qualified))
                .as(qualified)
                .isEmpty();
    }

    private static long candidate(JdbcTemplate jdbc) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO \"CANDIDATE\".\"CANDIDATES\" (\"FULL_NAME\") VALUES ('Synthetic catalog profile') RETURNING \"ID\"",
                        Long.class);
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"PROFILES\" (\"CANDIDATE_ID\",\"FULL_NAME\",\"BASIC\") VALUES (?,'Synthetic catalog profile','{\"synthetic\":true}')",
                id);
        return id;
    }

    private static UUID experience(JdbcTemplate jdbc, long candidate) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO \"CANDIDATE\".\"EXPERIENCES\" (\"ID\",\"CANDIDATE_ID\",\"COMPANY_RAW\",\"COMPANY_NORMALIZED\",\"TITLE_RAW\",\"FUNCTION_CODES\",\"INDUSTRY_CODES\",\"SENIORITY_CODE\",\"START_DATE\",\"START_PRECISION\",\"DATA\",\"STATE\",\"ORIGIN\") VALUES (?,?,'Aster Original','Aster Legacy','Orbit Original','[\"HR\"]','[\"LOGISTICS\"]','STAFF','2020-01-01','DAY','{\"synthetic\":true}','UNVERIFIED','MAPPING')",
                id,
                candidate);
        return id;
    }

    private static DriverManagerDataSource source(IsolatedPostgresSchema database)
            throws Exception {
        Map<String, Object> properties = new HashMap<>();
        database.configure((name, supplier) -> properties.put(name, supplier.get()));
        return new DriverManagerDataSource(
                database.jdbcUrl(),
                properties.get("spring.datasource.username").toString(),
                properties.get("spring.datasource.password").toString());
    }
}
