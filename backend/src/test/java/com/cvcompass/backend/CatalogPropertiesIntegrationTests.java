package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.catalog.TaxonomyCodes;
import com.cvcompass.backend.catalog.repository.NormalizationTaskRepository;
import com.cvcompass.backend.catalog.service.NormalizationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cv.catalog.import.max-entries=1",
            "cv.catalog.import.max-aliases-per-entry=1",
            "cv.catalog.taxonomy.resource-path=classpath:catalog/taxonomy-synthetic.json",
            "cv.catalog.taxonomy.version-label=cv-taxonomy/synthetic"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogPropertiesIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TaxonomyCodes taxonomy;
    @Autowired NormalizationTaskRepository tasks;
    @Autowired NormalizationService normalization;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(
                        CatalogPropertiesIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Test
    void configuredTaxonomyResourceAndVersionAreLoadedAndExposed() throws Exception {
        assertThat(taxonomy.version()).isEqualTo("cv-taxonomy/synthetic");
        assertThat(taxonomy.isActive("FUNCTION", "SYNTHETIC_FUNCTION")).isTrue();
        assertThat(taxonomy.isActive("FUNCTION", "IT")).isFalse();
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT \"TAXONOMY_VERSION\" FROM \"CATALOG\".\"TAXONOMY_CODES\"",
                                String.class))
                .containsExactly(taxonomy.version());
        taxonomy.load();
        assertThat(taxonomy.isActive("FUNCTION", "SYNTHETIC_FUNCTION")).isTrue();
    }

    @Test
    @Transactional
    void normalizationClaimUsesConfiguredTaxonomyVersionAndCodes() {
        tasks.enqueue("COMPANY", "synthetic version", "Synthetic version", null);
        var claim = normalization.claim(1);
        assertThat(claim.taxonomyVersion()).isEqualTo("cv-taxonomy/synthetic");
        assertThat(claim.taxonomy().get("FUNCTION"))
                .extracting(code -> code.code())
                .containsExactly("SYNTHETIC_FUNCTION");
        assertThat(claim.items())
                .singleElement()
                .satisfies(item -> assertThat(item.raw()).isEqualTo("Synthetic version"));
    }

    @Test
    void configuredEntryAndAliasLimitsReturn422WithoutWritesAndAcceptTheBoundary()
            throws Exception {
        jdbc.update(
                "INSERT INTO \"IDENTITY\".\"USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-http',?,'ADMIN') ON CONFLICT (\"USERNAME\") DO UPDATE SET \"ROLE\"='ADMIN',\"PASSWORD_HASH\"=EXCLUDED.\"PASSWORD_HASH\"",
                new BCryptPasswordEncoder(4).encode("synthetic-http-test-only"));
        var admin = TestUserSession.login(client, port, jdbc, json);
        String company =
                """
                {"name":"Synthetic Limited Company","industry":"SYNTHETIC_INDUSTRY","aliases":["Synthetic Limited Alias"]}
                """;
        for (String body :
                List.of(
                        seed(company + "," + company),
                        seed(
                                company.replace(
                                        "[\"Synthetic Limited Alias\"]",
                                        "[\"Synthetic Alias One\",\"Synthetic Alias Two\"]")))) {
            var before = rows();
            var response = post(admin, body);
            assertThat(response.statusCode()).isEqualTo(422);
            var error = json.readTree(response.body());
            assertThat(error.path("code").asString()).isEqualTo("SEED_TOO_LARGE");
            assertThat(error.path("message").asString()).isEqualTo("Invalid catalog seed");
            assertThat(error.path("details").size()).isEqualTo(1);
            assertThat(error.path("details").get(0).path("field").asString())
                    .isEqualTo(
                            body.equals(seed(company + "," + company))
                                    ? "body"
                                    : "companies[0].aliases");
            assertThat(rows()).isEqualTo(before);
        }
        var boundary = post(admin, seed(company));
        assertThat(boundary.statusCode()).isEqualTo(200);
        assertThat(json.readTree(boundary.body()))
                .isEqualTo(json.readTree("{\"created\":3,\"updated\":0,\"unchanged\":0}"));
    }

    private static String seed(String companies) {
        return "{\"schemaVersion\":\"catalog-seed/0\",\"companies\":["
                + companies
                + "],\"titles\":[]}";
    }

    private List<List<String>> rows() {
        return List.of(
                jdbc.queryForList(
                        "SELECT row_to_json(e)::text FROM \"CATALOG\".\"ENTRIES\" e ORDER BY \"ID\"",
                        String.class),
                jdbc.queryForList(
                        "SELECT row_to_json(a)::text FROM \"CATALOG\".\"ALIASES\" a ORDER BY \"ID\"",
                        String.class));
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
}
