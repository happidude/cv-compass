package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.catalog.api.CatalogText;
import com.cvcompass.backend.search.dto.CandidateSearchRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

abstract class CandidateSearchTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();
    TestUserSession session;

    void login() throws Exception {
        session = TestUserSession.login(client, port, jdbc, json);
    }

    long company(String name, Long parent, String origin) {
        return jdbc.queryForObject(
                """
                INSERT INTO "CATALOG"."ENTRIES" ("KIND","NAME","NAME_FOLDED","PARENT_ID","ORIGIN")
                VALUES ('COMPANY',?,?,?,?) RETURNING "ID"
                """,
                Long.class,
                name,
                CatalogText.fold(name),
                parent,
                origin);
    }

    long title(String name) {
        return jdbc.queryForObject(
                """
                INSERT INTO "CATALOG"."ENTRIES" ("KIND","NAME","NAME_FOLDED","FUNCTION_CODES","SENIORITY_CODE","ORIGIN")
                VALUES ('TITLE',?,?,'{IT}','MANAGER','AI') RETURNING "ID"
                """,
                Long.class,
                name,
                CatalogText.fold(name));
    }

    void alias(String kind, String name, Long entry, String state) {
        jdbc.update(
                """
                INSERT INTO "CATALOG"."ALIASES" ("KIND","ALIAS_FOLDED","ALIAS_RAW","ENTRY_ID","STATE","ORIGIN")
                VALUES (?,?,?,?,?,?)
                """,
                kind,
                CatalogText.fold(name),
                name,
                entry,
                state,
                entry == null ? null : "USER");
    }

    long candidate(
            String text,
            Long company,
            Long parent,
            Long title,
            Long currentCompany,
            Long currentTitle,
            String province,
            Integer months) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO \"CANDIDATE\".\"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"",
                        Long.class);
        jdbc.update(
                """
                INSERT INTO "CANDIDATE"."PROFILES" ("CANDIDATE_ID","FULL_NAME","CURRENT_COMPANY_ID","CURRENT_TITLE_ID")
                VALUES (?,'Synthetic Search Profile',?,?)
                """,
                id,
                currentCompany,
                currentTitle);
        String folded = CatalogText.fold(text);
        jdbc.update(
                """
                INSERT INTO "SEARCH"."CANDIDATE_INDEX" ("CANDIDATE_ID","SEARCH_TEXT","SEARCH_TSV",
                  "COMPANY_IDS","PARENT_COMPANY_IDS","TITLE_IDS","FUNCTION_CODES","SENIORITY_CODES",
                  "SCOPE_CODES","INDUSTRY_CODES","LOCATION_PROVINCE","TOTAL_EXPERIENCE_MONTHS","UPDATED_AT")
                VALUES (?,?,to_tsvector('simple',?),?::bigint[],?::bigint[],?::bigint[],
                  '{IT}','{MANAGER}','{COMPANY}','{LOGISTICS}',?,?,now())
                """,
                id,
                folded,
                folded,
                ids(company),
                ids(parent),
                ids(title),
                province,
                months);
        return id;
    }

    private static Long[] ids(Long id) {
        return id == null ? new Long[0] : new Long[] {id};
    }

    String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().replace("-", "");
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(
                session.request(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode search(String query) throws Exception {
        var response = get("/api/candidates/search?" + query);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    List<Long> resultIds(JsonNode response) {
        var result = new java.util.ArrayList<Long>();
        response.path("content").forEach(row -> result.add(row.path("id").asLong()));
        return result;
    }

    static CandidateSearchRequest request(String... parameters) {
        var values = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < parameters.length; i += 2) values.add(parameters[i], parameters[i + 1]);
        return CandidateSearchRequest.from(values);
    }

    BonusFixture positionBonusFixture() {
        return positionBonusFixture(false);
    }

    BonusFixture positionBonusFixture(boolean useCompanyId) {
        String marker = unique("bonusmarker");
        String companyName = unique("Bonus Company");
        String titleName = unique("Bonus Title");
        long company = company(companyName, null, "USER");
        long title = title(titleName);
        String text = marker + " " + companyName + " " + titleName;
        long currentCompany = candidate(text, company, null, title, company, null, null, 0);
        long currentTitle = candidate(text, company, null, title, null, title, null, 0);
        return new BonusFixture(
                "q="
                        + encode(marker)
                        + (useCompanyId
                                ? "&companyId=" + company
                                : "&company=" + encode(companyName))
                        + "&title="
                        + encode(titleName),
                currentCompany,
                currentTitle);
    }

    record BonusFixture(String query, long currentCompany, long currentTitle) {}
}
