package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.catalog.TaxonomyCodes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogImportIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TaxonomyCodes taxonomy;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(CatalogImportIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Test
    void startupLoadsApprovedTaxonomyAndReloadReactivatesCurrentAndRetiresMissingCodes()
            throws Exception {
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"KIND\",count(*) AS count FROM \"CATALOG\".\"TAXONOMY_CODES\" WHERE \"ACTIVE\" GROUP BY \"KIND\" ORDER BY \"KIND\""))
                .containsExactly(
                        Map.of("KIND", "FUNCTION", "count", 20L),
                        Map.of("KIND", "INDUSTRY", "count", 16L),
                        Map.of("KIND", "SCOPE", "count", 3L),
                        Map.of("KIND", "SENIORITY", "count", 7L));
        assertThat(taxonomy.isActive("SCOPE", "null")).isFalse();
        assertThat(taxonomy.isActive("SENIORITY", "OTHER")).isFalse();
        jdbc.update(
                "UPDATE \"CATALOG\".\"TAXONOMY_CODES\" SET \"ACTIVE\"=false,\"LABEL_VI\"='Synthetic stale label' WHERE \"KIND\"='FUNCTION' AND \"CODE\"='IT'");
        jdbc.update(
                "INSERT INTO \"CATALOG\".\"TAXONOMY_CODES\" VALUES ('FUNCTION','SYNTHETIC_RETIRED','Mã tổng hợp','cv-taxonomy/0',true)");
        taxonomy.load();
        assertThat(taxonomy.isActive("FUNCTION", "IT")).isTrue();
        assertThat(taxonomy.isActive("FUNCTION", "SYNTHETIC_RETIRED")).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"LABEL_VI\" FROM \"CATALOG\".\"TAXONOMY_CODES\" WHERE \"KIND\"='FUNCTION' AND \"CODE\"='IT'",
                                String.class))
                .isEqualTo("Công nghệ thông tin");
        var before = taxonomyRows();
        taxonomy.load();
        assertThat(taxonomyRows()).containsExactlyElementsOf(before);
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT \"TAXONOMY_VERSION\" FROM \"CATALOG\".\"TAXONOMY_CODES\"",
                                String.class))
                .containsExactly("cv-taxonomy/0");
    }

    @Test
    void adminImportCreatesParentsNamesAndAliasesAndRerunDoesNotChangeRowsOrTimestamps()
            throws Exception {
        var session = session("ADMIN");
        String seed = seed("Create", "MANUFACTURING", "IT");
        var first = post(session, seed);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()))
                .isEqualTo(json.readTree("{\"created\":8,\"updated\":0,\"unchanged\":0}"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT p.\"NAME\" FROM \"CATALOG\".\"COMPANIES\" c JOIN \"CATALOG\".\"COMPANIES\" p ON p.\"ID\"=c.\"PARENT_ID\" WHERE c.\"NAME\"='Aster Works Create'",
                                String.class))
                .isEqualTo("Helix Group Create");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"INDUSTRY_CODE\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME\"='Helix Group Create'",
                                String.class))
                .isNull();
        var before = catalogRows();
        var second = post(session, seed);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()))
                .isEqualTo(json.readTree("{\"created\":0,\"updated\":0,\"unchanged\":8}"));
        assertThat(catalogRows()).isEqualTo(before);
        var updated = post(session, seed("Create", "RETAIL", "SALES"));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).path("updated").asInt()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"INDUSTRY_CODE\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME\"='Aster Works Create'",
                                String.class))
                .isEqualTo("RETAIL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"FUNCTION_CODES\"[1] FROM \"CATALOG\".\"TITLES\" WHERE \"NAME\"='Orbit Lead Create'",
                                String.class))
                .isEqualTo("SALES");
    }

    @Test
    void resolvesExplicitParentRegardlessOfOrderAndAcceptsTwoFunctionsWithOptionalScope()
            throws Exception {
        var session = session("ADMIN");
        String seed =
                """
                {"schemaVersion":"catalog-seed/0",
                 "companies":[
                   {"name":"Aster Ordered","parent":"HELIX Ordered","industry":"RETAIL","aliases":[" Aster\\tOrdered "]},
                   {"name":"Helix Ordered","industry":"MANUFACTURING","aliases":[]}],
                 "titles":[{"name":"Orbit Ordered","functions":["IT","PROJECT_MANAGEMENT"],"seniority":"HEAD","aliases":[]}]}
                """;
        var first = post(session, seed);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"INDUSTRY_CODE\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='helix ordered'",
                                String.class))
                .isEqualTo("MANUFACTURING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"SCOPE_CODE\" FROM \"CATALOG\".\"TITLES\" WHERE \"NAME_FOLDED\"='orbit ordered'",
                                String.class))
                .isNull();
        var before = catalogRows();
        var second = post(session, seed);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("created").asInt()).isZero();
        assertThat(json.readTree(second.body()).path("updated").asInt()).isZero();
        assertThat(catalogRows()).isEqualTo(before);
    }

    @Test
    void rejectsUnknownInactiveAndOtherCodesAndAllInvalidShapesWithoutAnyWrites() throws Exception {
        var session = session("ADMIN");
        jdbc.update(
                "INSERT INTO \"CATALOG\".\"TAXONOMY_CODES\" VALUES ('INDUSTRY','SYNTHETIC_INACTIVE','Mã tổng hợp','cv-taxonomy/0',false)");
        for (String body :
                List.of(
                        seed("Invalid", "UNKNOWN", "IT"),
                        seed("Invalid", "SYNTHETIC_INACTIVE", "IT"),
                        seed("Invalid", "OTHER", "IT"),
                        seed("Invalid", "RETAIL", "OTHER"),
                        seed("Invalid", "RETAIL", "IT").replace("catalog-seed/0", "catalog-seed/9"),
                        seed("Invalid", "RETAIL", "IT")
                                .replace("\"seniority\":\"MANAGER\"", "\"seniority\":\"OTHER\""),
                        seed("Invalid", "RETAIL", "IT")
                                .replace("\"scope\":\"COMPANY\"", "\"scope\":\"UNKNOWN\""),
                        seed("Invalid", "RETAIL", "IT")
                                .replace("[\"IT\"]", "[\"IT\",\"SALES\",\"HR\"]"),
                        seed("Invalid", "RETAIL", "IT").replace("Aster Works Invalid", " "),
                        "{\"schemaVersion\":\"catalog-seed/0\",\"companies\":[null],\"titles\":null}")) {
            var before = catalogRows();
            var response = post(session, body);
            assertThat(response.statusCode()).isEqualTo(422);
            JsonNode error = json.readTree(response.body());
            assertThat(error.path("code").asString()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.path("details").isArray()).isTrue();
            assertThat(error.path("details").size()).isPositive();
            assertThat(error.path("details").get(0).path("field").asString()).isNotBlank();
            assertThat(response.body()).doesNotContain("Aster", "Orbit", "Helix");
            assertThat(catalogRows()).isEqualTo(before);
        }
    }

    @Test
    void rejectsParentCyclesWithinOneSeedAndAcrossImportsWithNoWrites() throws Exception {
        var admin = session("ADMIN");
        assertRejected(
                admin,
                companySeed(
                        """
                [{"name":"Aster Cycle Local","parent":"Helix Cycle Local","industry":"RETAIL"},
                 {"name":"Helix Cycle Local","parent":"Aster Cycle Local","industry":"RETAIL"}]
                """),
                "VALIDATION_ERROR",
                "companies[0].parent");
        assertThat(
                        post(
                                        admin,
                                        companySeed(
                                                """
                [{"name":"Helix Cycle Stored","parent":"Aster Cycle Stored","industry":"RETAIL"}]
                """))
                                .statusCode())
                .isEqualTo(200);
        assertRejected(
                admin,
                companySeed(
                        """
                [{"name":"Aster Cycle Stored","parent":"Helix Cycle Stored","industry":"RETAIL"}]
                """),
                "VALIDATION_ERROR",
                "companies[0].parent");
    }

    @Test
    void rejectsCompanyAndTitleAliasCollisionsInsteadOfMovingAliases() throws Exception {
        var admin = session("ADMIN");
        assertThat(post(admin, seed("AliasOwner", "RETAIL", "IT")).statusCode()).isEqualTo(200);
        assertRejected(
                admin,
                seed("AliasCompany", "RETAIL", "IT")
                        .replace("Aster Alias AliasCompany", "Aster Alias AliasOwner"),
                "ALIAS_OWNED_BY_OTHER_ENTRY",
                "companies[0].aliases[0]");
        assertRejected(
                admin,
                seed("AliasTitle", "RETAIL", "IT")
                        .replace("Orbit Alias AliasTitle", "Orbit Alias AliasOwner"),
                "ALIAS_OWNED_BY_OTHER_ENTRY",
                "titles[0].aliases[0]");
        assertThat(post(admin, seed("AliasExisting", "RETAIL", "IT")).statusCode()).isEqualTo(200);
        assertRejected(
                admin,
                seed("AliasExisting", "RETAIL", "IT")
                        .replace("Aster Alias AliasExisting", "Aster Alias AliasOwner"),
                "ALIAS_OWNED_BY_OTHER_ENTRY",
                "companies[0].aliases[0]");
        assertRejected(
                admin,
                seed("AliasExisting", "RETAIL", "IT")
                        .replace("Orbit Alias AliasExisting", "Orbit Alias AliasOwner"),
                "ALIAS_OWNED_BY_OTHER_ENTRY",
                "titles[0].aliases[0]");
    }

    @Test
    void resolvesCompanyAndTitleNamesThroughExistingAliasesAndRemainsIdempotent() throws Exception {
        var admin = session("ADMIN");
        assertThat(post(admin, seed("Resolved", "RETAIL", "IT")).statusCode()).isEqualTo(200);
        long company =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='aster works resolved'",
                        Long.class);
        long title =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CATALOG\".\"TITLES\" WHERE \"NAME_FOLDED\"='orbit lead resolved'",
                        Long.class);
        var ownersBefore = aliasOwners();
        String throughAliases =
                seed("Resolved", "MANUFACTURING", "SALES")
                        .replace(
                                "\"name\":\"Aster Works Resolved\"",
                                "\"name\":\"Aster Alias Resolved\"")
                        .replace(
                                "\"name\":\"Orbit Lead Resolved\"",
                                "\"name\":\"Orbit Alias Resolved\"");
        var response = post(admin, throughAliases);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("created").asInt()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='aster alias resolved'",
                                Long.class))
                .isEqualTo(company);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"ID\" FROM \"CATALOG\".\"TITLES\" WHERE \"NAME_FOLDED\"='orbit alias resolved'",
                                Long.class))
                .isEqualTo(title);
        assertThat(aliasOwners()).isEqualTo(ownersBefore);
        var before = catalogRows();
        response = post(admin, throughAliases);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("created").asInt()).isZero();
        assertThat(json.readTree(response.body()).path("updated").asInt()).isZero();
        assertThat(catalogRows()).isEqualTo(before);
        jdbc.update(
                "UPDATE \"CATALOG\".\"COMPANIES\" SET \"ORIGIN\"='USER' WHERE \"ID\"=?", company);
        jdbc.update("UPDATE \"CATALOG\".\"TITLES\" SET \"ORIGIN\"='USER' WHERE \"ID\"=?", title);
        before = catalogRows();
        // The former canonical names remain aliases of these USER-owned rows.
        response = post(admin, seed("Resolved", "RETAIL", "IT"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("created").asInt()).isZero();
        assertThat(json.readTree(response.body()).path("updated").asInt()).isZero();
        assertThat(catalogRows()).isEqualTo(before);
    }

    @Test
    void permitsNullIndustryOnlyForReferencedOrExistingParentsAndResolvesParentAliases()
            throws Exception {
        var admin = session("ADMIN");
        String initial =
                companySeed(
                        """
                [{"name":"Aster Child ParentOnly","parent":"Helix ParentOnly","industry":"RETAIL"},
                 {"name":"Helix ParentOnly","industry":null,"aliases":["Helix Alternate ParentOnly"]}]
                """);
        assertThat(post(admin, initial).statusCode()).isEqualTo(200);
        long parent =
                jdbc.queryForObject(
                        "SELECT \"ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='helix parentonly'",
                        Long.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"INDUSTRY_CODE\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"ID\"=?",
                                String.class,
                                parent))
                .isNull();
        assertThat(
                        post(
                                        admin,
                                        companySeed(
                                                "[{\"name\":\"Helix ParentOnly\",\"industry\":null}]"))
                                .statusCode())
                .isEqualTo(200);
        String child =
                companySeed(
                        "[{\"name\":\"Aster Second ParentOnly\",\"parent\":\"Helix Alternate ParentOnly\",\"industry\":\"RETAIL\"}]");
        assertThat(post(admin, child).statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"PARENT_ID\" FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='aster second parentonly'",
                                Long.class))
                .isEqualTo(parent);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM \"CATALOG\".\"COMPANIES\" WHERE \"NAME_FOLDED\"='helix alternate parentonly'",
                                Integer.class))
                .isZero();
        var before = catalogRows();
        var repeated = post(admin, child);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(repeated.body()).path("created").asInt()).isZero();
        assertThat(json.readTree(repeated.body()).path("updated").asInt()).isZero();
        assertThat(catalogRows()).isEqualTo(before);
        assertRejected(
                admin,
                companySeed("[{\"name\":\"Aster Standalone ParentOnly\",\"industry\":null}]"),
                "VALIDATION_ERROR",
                "companies[0].industry");
    }

    @Test
    void nullOrMissingArraysReturn422AndOversizedSeedsHaveAnExplicitErrorCode() throws Exception {
        var admin = session("ADMIN");
        assertRejected(admin, "null", "VALIDATION_ERROR", "body");
        assertRejected(
                admin,
                "{\"schemaVersion\":\"catalog-seed/0\",\"titles\":[]}",
                "VALIDATION_ERROR",
                "companies");
        assertRejected(
                admin,
                "{\"schemaVersion\":\"catalog-seed/0\",\"companies\":[]}",
                "VALIDATION_ERROR",
                "titles");
        assertRejected(
                admin,
                "{\"schemaVersion\":\"catalog-seed/0\",\"companies\":null,\"titles\":null}",
                "VALIDATION_ERROR",
                "companies");
        String manyCompanies = String.join(",", Collections.nCopies(5000, "null"));
        String manyTitles = String.join(",", Collections.nCopies(5001, "null"));
        assertRejected(
                admin,
                "{\"schemaVersion\":\"catalog-seed/0\",\"companies\":["
                        + manyCompanies
                        + "],\"titles\":["
                        + manyTitles
                        + "]}",
                "SEED_TOO_LARGE",
                "body");
        String aliases = json.writeValueAsString(Collections.nCopies(51, "Synthetic Limit Alias"));
        assertRejected(
                admin,
                seed("LimitCompany", "RETAIL", "IT")
                        .replace("[\"Aster Alias LimitCompany\"]", aliases),
                "SEED_TOO_LARGE",
                "companies[0].aliases");
        assertRejected(
                admin,
                seed("LimitTitle", "RETAIL", "IT").replace("[\"Orbit Alias LimitTitle\"]", aliases),
                "SEED_TOO_LARGE",
                "titles[0].aliases");
        String boundaryAliases =
                json.writeValueAsString(Collections.nCopies(50, "Synthetic Boundary Alias"));
        assertThat(
                        post(
                                        admin,
                                        seed("LimitBoundary", "RETAIL", "IT")
                                                .replace(
                                                        "[\"Aster Alias LimitBoundary\"]",
                                                        boundaryAliases)
                                                .replace(
                                                        "[\"Orbit Alias LimitBoundary\"]",
                                                        boundaryAliases))
                                .statusCode())
                .isEqualTo(200);
    }

    @Test
    void authenticatedPostWithoutCsrfIs403AndAnonymousPostWithValidCsrfIs401() throws Exception {
        var admin = session("ADMIN");
        var before = catalogRows();
        URI endpoint = URI.create("http://localhost:" + port + "/api/admin/catalog/import");
        String cookies =
                admin.request(endpoint).GET().build().headers().firstValue("Cookie").orElseThrow();
        var missingCsrf =
                client.send(
                        HttpRequest.newBuilder(endpoint)
                                .header("Cookie", cookies)
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                seed("NoCsrf", "RETAIL", "IT")))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(missingCsrf.statusCode()).isEqualTo(403);
        var csrf =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/api/auth/csrf"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        String anonymousCookies =
                String.join(
                        "; ",
                        csrf.headers().allValues("Set-Cookie").stream()
                                .map(value -> value.split(";", 2)[0])
                                .toList());
        var anonymous =
                client.send(
                        HttpRequest.newBuilder(endpoint)
                                .header("Cookie", anonymousCookies)
                                .header(
                                        "X-XSRF-TOKEN",
                                        json.readTree(csrf.body()).path("token").asString())
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                seed("Anonymous", "RETAIL", "IT")))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(catalogRows()).isEqualTo(before);
    }

    private void assertRejected(TestUserSession session, String body, String code, String field)
            throws Exception {
        var before = catalogRows();
        var response = post(session, body);
        assertThat(response.statusCode()).isEqualTo(422);
        var error = json.readTree(response.body());
        assertThat(error.path("code").asString()).isEqualTo(code);
        var fields = new ArrayList<String>();
        for (var violation : error.path("details")) fields.add(violation.path("field").asString());
        assertThat(fields).contains(field);
        assertThat(catalogRows()).isEqualTo(before);
    }

    private static String companySeed(String companies) {
        return "{\"schemaVersion\":\"catalog-seed/0\",\"companies\":"
                + companies
                + ",\"titles\":[]}";
    }

    private List<List<Map<String, Object>>> aliasOwners() {
        return List.of(
                jdbc.queryForList(
                        "SELECT \"ALIAS_FOLDED\",\"COMPANY_ID\" FROM \"CATALOG\".\"COMPANY_ALIASES\" ORDER BY \"ALIAS_FOLDED\""),
                jdbc.queryForList(
                        "SELECT \"ALIAS_FOLDED\",\"TITLE_ID\" FROM \"CATALOG\".\"TITLE_ALIASES\" ORDER BY \"ALIAS_FOLDED\""));
    }

    @Test
    void rollsBackEarlierCatalogWritesWhenTheDatabaseRejectsTheLastAlias() throws Exception {
        var session = session("ADMIN");
        var before = catalogRows();
        // Fail after company, parent, title and earlier alias writes in this private database.
        jdbc.execute(
                "ALTER TABLE \"CATALOG\".\"TITLE_ALIASES\" ADD CONSTRAINT \"SYNTHETIC_IMPORT_ROLLBACK\" CHECK (\"ALIAS_RAW\" <> 'Orbit Alias Rollback')");
        try {
            var response = post(session, seed("Rollback", "RETAIL", "IT"));
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).doesNotContain("Aster", "Orbit", "Helix");
            assertThat(catalogRows()).isEqualTo(before);
        } finally {
            jdbc.execute(
                    "ALTER TABLE \"CATALOG\".\"TITLE_ALIASES\" DROP CONSTRAINT \"SYNTHETIC_IMPORT_ROLLBACK\"");
        }
    }

    @Test
    void nonAdminIsForbiddenBeforeImportAndUserRowsAndAliasesArePreserved() throws Exception {
        var user = session("USER");
        var before = catalogRows();
        var denied = post(user, seed("Denied", "RETAIL", "IT"));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(denied.body()).path("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(catalogRows()).isEqualTo(before);
        var admin = session("ADMIN");
        assertThat(post(admin, seed("Protected", "RETAIL", "IT")).statusCode()).isEqualTo(200);
        jdbc.update(
                "UPDATE \"CATALOG\".\"COMPANIES\" SET \"ORIGIN\"='USER' WHERE \"NAME\" LIKE '%Protected'");
        jdbc.update(
                "UPDATE \"CATALOG\".\"TITLES\" SET \"ORIGIN\"='USER' WHERE \"NAME\" LIKE '%Protected'");
        jdbc.update(
                "UPDATE \"CATALOG\".\"COMPANY_ALIASES\" SET \"ORIGIN\"='USER' WHERE \"ALIAS_RAW\" LIKE '%Protected'");
        jdbc.update(
                "UPDATE \"CATALOG\".\"TITLE_ALIASES\" SET \"ORIGIN\"='USER' WHERE \"ALIAS_RAW\" LIKE '%Protected'");
        before = catalogRows();
        var response = post(admin, seed("Protected", "MANUFACTURING", "SALES"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("created").asInt()).isZero();
        assertThat(json.readTree(response.body()).path("updated").asInt()).isZero();
        assertThat(catalogRows()).isEqualTo(before);
    }

    private TestUserSession session(String role) throws Exception {
        jdbc.update(
                "INSERT INTO \"IDENTITY\".\"USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-http','synthetic-unused',?) ON CONFLICT (\"USERNAME\") DO UPDATE SET \"ROLE\"=EXCLUDED.\"ROLE\"",
                role);
        // The shared helper supplies a test-only password when it creates the account.
        jdbc.update(
                "UPDATE \"IDENTITY\".\"USERS\" SET \"PASSWORD_HASH\"=? WHERE \"USERNAME\"='synthetic-http'",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
                        .encode("synthetic-http-test-only"));
        return TestUserSession.login(client, port, jdbc, json);
    }

    private HttpResponse<String> post(TestUserSession session, String body) throws Exception {
        return client.send(
                session.request(
                                URI.create(
                                        "http://localhost:" + port + "/api/admin/catalog/import"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String seed(String suffix, String industry, String function) {
        return """
                {"schemaVersion":"catalog-seed/0",
                 "companies":[{"name":"Aster Works %s","parent":"Helix Group %s","industry":"%s","aliases":["Aster Alias %s"]}],
                 "titles":[{"name":"Orbit Lead %s","functions":["%s"],"seniority":"MANAGER","scope":"COMPANY","aliases":["Orbit Alias %s"]}]}
                """
                .formatted(suffix, suffix, industry, suffix, suffix, function, suffix);
    }

    private List<Map<String, Object>> taxonomyRows() {
        return jdbc.queryForList(
                "SELECT * FROM \"CATALOG\".\"TAXONOMY_CODES\" ORDER BY \"KIND\",\"CODE\"");
    }

    private List<List<String>> catalogRows() {
        return List.of(
                jdbc.queryForList(
                        "SELECT row_to_json(t)::text FROM \"CATALOG\".\"COMPANIES\" t ORDER BY \"ID\"",
                        String.class),
                jdbc.queryForList(
                        "SELECT row_to_json(t)::text FROM \"CATALOG\".\"TITLES\" t ORDER BY \"ID\"",
                        String.class),
                jdbc.queryForList(
                        "SELECT row_to_json(t)::text FROM \"CATALOG\".\"COMPANY_ALIASES\" t ORDER BY \"ALIAS_FOLDED\"",
                        String.class),
                jdbc.queryForList(
                        "SELECT row_to_json(t)::text FROM \"CATALOG\".\"TITLE_ALIASES\" t ORDER BY \"ALIAS_FOLDED\"",
                        String.class));
    }
}
