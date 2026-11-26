package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.service.MappingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cv.mapping.enabled=false",
            "cv.candidate.publication.enabled=false",
            "cv.mapping.remap.list-limit=2"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RemapAdminIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private final HttpClient client = HttpClient.newHttpClient();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MappingService mapping;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(RemapAdminIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Test
    void remapAdminValidatesScopesReturnsProgressAndProtectsAllEndpoints() throws Exception {
        var tree =
                (ObjectNode)
                        json.readTree(
                                getClass()
                                        .getResourceAsStream(
                                                "/mapping/personal-info-config-v3.5.json"));
        tree.put("revision", "synthetic-admin-" + UUID.randomUUID());
        var definition = json.treeToValue(tree, PersonalInfoDefinition.class);
        mapping.activate(mapping.register(definition.revision(), definition));
        var user = session("USER");
        String path = "/api/admin/mapping/remaps";
        assertThat(post(user, path, "{\"scope\":\"ALL\"}").statusCode()).isEqualTo(403);
        assertThat(get(user, path).statusCode()).isEqualTo(403);
        var admin = session("ADMIN");
        for (String invalid :
                List.of(
                        "{}",
                        "{\"scope\":\"OTHER\"}",
                        "{\"scope\":\"BATCH\"}",
                        "{\"scope\":\"BATCH\",\"batchId\":0}",
                        "{\"scope\":\"DATE_RANGE\",\"from\":\"2026-11-24\"}",
                        "{\"scope\":\"DATE_RANGE\",\"from\":\"2026-11-25\",\"to\":\"2026-11-24\"}",
                        "{\"scope\":\"ISSUE\"}",
                        "{\"scope\":\"ISSUE\",\"issueCode\":\"DOES_NOT_EXIST\"}")) {
            var response = post(admin, path, invalid);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asString())
                    .isEqualTo("INVALID_REMAP_SCOPE");
        }
        for (int i = 0; i < 3; i++)
            assertThat(post(admin, path, "{\"scope\":\"ALL\"}").statusCode()).isEqualTo(200);
        var recent = json.readTree(get(admin, path).body());
        assertThat(recent.size()).isEqualTo(2);
        long id = recent.get(0).path("id").asLong();
        var run = json.readTree(get(admin, path + "/" + id).body());
        assertThat(run.path("selected").asLong()).isZero();
        assertThat(run.path("pending").asLong()).isZero();
        assertThat(run.path("completed").asLong()).isZero();
        assertThat(run.path("failed").asLong()).isZero();
        assertThat(
                        json.readTree(post(admin, path + "/" + id + "/pause", "").body())
                                .path("state")
                                .asString())
                .isEqualTo("PAUSED");
        assertThat(
                        json.readTree(post(admin, path + "/" + id + "/resume", "").body())
                                .path("state")
                                .asString())
                .isEqualTo("RUNNING");
        assertThat(get(admin, path + "/9223372036854775807").statusCode()).isEqualTo(404);
        user = session("USER");
        assertThat(get(user, path + "/" + id).statusCode()).isEqualTo(403);
        assertThat(post(user, path + "/" + id + "/pause", "").statusCode()).isEqualTo(403);
        assertThat(post(user, path + "/" + id + "/resume", "").statusCode()).isEqualTo(403);
    }

    @Test
    void aliasRequeueFiltersFailuresResetsLeaseAndNeverTouchesDoneOrUserRows() throws Exception {
        long company = alias("COMPANY", "FAILED", null, "SYNTHETIC_RETRY");
        long title = alias("TITLE", "FAILED", "AI", "SYNTHETIC_RETRY");
        long otherError = alias("COMPANY", "FAILED", null, "OTHER_RETRY");
        long userOwned = alias("COMPANY", "FAILED", "USER", "SYNTHETIC_RETRY");
        long done = alias("COMPANY", "DONE", "AI", null);
        long pending = alias("COMPANY", "PENDING", null, null);
        String userBefore = row(userOwned), doneBefore = row(done), pendingBefore = row(pending);
        String path = "/api/admin/catalog/aliases/requeue";
        var user = session("USER");
        assertThat(post(user, path, "").statusCode()).isEqualTo(403);
        assertThat(row(company)).contains("FAILED");
        var admin = session("ADMIN");
        var invalid = post(admin, path + "?kind=WRONG", "");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json.readTree(invalid.body()).path("code").asString())
                .isEqualTo("INVALID_ALIAS_KIND");
        assertThat(requeued(post(admin, path + "?kind=COMPANY&errorCode=SYNTHETIC_RETRY", "")))
                .isEqualTo(1);
        assertThat(requeued(post(admin, path + "?kind=COMPANY&errorCode=SYNTHETIC_RETRY", "")))
                .isZero();
        var after = json.readTree(row(company));
        assertThat(after.path("STATE").asString()).isEqualTo("PENDING");
        assertThat(after.path("ATTEMPTS").asInt()).isZero();
        for (String column : List.of("ERROR_CODE", "TOKEN", "LEASE_EXPIRES_AT", "COMPLETED_AT"))
            assertThat(after.path(column).isNull()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"NEXT_ATTEMPT_AT\"<=clock_timestamp() FROM \"CATALOG\".\"ALIASES\" WHERE \"ID\"=?",
                                Boolean.class,
                                company))
                .isTrue();
        assertThat(row(title)).contains("FAILED");
        assertThat(row(otherError)).contains("FAILED");
        assertThat(requeued(post(admin, path, ""))).isEqualTo(2);
        assertThat(requeued(post(admin, path, ""))).isZero();
        assertThat(row(userOwned)).isEqualTo(userBefore);
        assertThat(row(done)).isEqualTo(doneBefore);
        assertThat(row(pending)).isEqualTo(pendingBefore);
    }

    private int requeued(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("requeued").asInt();
    }

    private long alias(String kind, String state, String origin, String error) {
        Long entry =
                "DONE".equals(state)
                        ? jdbc.queryForObject(
                                "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES (?,'Synthetic alias target',?,'AI') RETURNING \"ID\"",
                                Long.class,
                                kind,
                                UUID.randomUUID().toString())
                        : null;
        return jdbc.queryForObject(
                """
                INSERT INTO "CATALOG"."ALIASES" ("KIND","ALIAS_FOLDED","ALIAS_RAW","STATE","ORIGIN","ENTRY_ID","ATTEMPTS","ERROR_CODE","TOKEN","LEASE_EXPIRES_AT","NEXT_ATTEMPT_AT","COMPLETED_AT")
                VALUES (?,?,'Synthetic alias',?,?,?,3,?,?,'2026-01-01T00:00:00Z','2099-01-01T00:00:00Z','2026-01-01T00:00:00Z') RETURNING "ID"
                """,
                Long.class,
                kind,
                UUID.randomUUID().toString(),
                state,
                origin,
                entry,
                error,
                UUID.randomUUID());
    }

    private String row(long id) {
        return jdbc.queryForObject(
                "SELECT to_jsonb(a)::text FROM \"CATALOG\".\"ALIASES\" a WHERE \"ID\"=?",
                String.class,
                id);
    }

    private TestUserSession session(String role) throws Exception {
        jdbc.update(
                "INSERT INTO \"IDENTITY\".\"USERS\" (\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-http',?,?) ON CONFLICT (\"USERNAME\") DO UPDATE SET \"ROLE\"=EXCLUDED.\"ROLE\",\"PASSWORD_HASH\"=EXCLUDED.\"PASSWORD_HASH\"",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
                        .encode("synthetic-http-test-only"),
                role);
        return TestUserSession.login(client, port, jdbc, json);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> post(TestUserSession session, String path, String body)
            throws Exception {
        return client.send(
                session.request(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(TestUserSession session, String path) throws Exception {
        return client.send(
                session.request(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
