package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.search.config.SearchProperties;
import com.cvcompass.backend.search.repository.CandidateSearchRepository;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cv.candidate.publication.enabled=false", "cv.search.nightly-cron=-"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateSearchIntegrationTests extends CandidateSearchTestSupport {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    @Autowired CandidateSearchRepository searchRepository;
    @Autowired SearchProperties searchProperties;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(
                        CandidateSearchIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @BeforeEach
    void setup() throws Exception {
        login();
        for (String[] code :
                new String[][] {
                    {"FUNCTION", "IT"},
                    {"FUNCTION", "SALES"},
                    {"SENIORITY", "MANAGER"},
                    {"SCOPE", "COMPANY"},
                    {"INDUSTRY", "LOGISTICS"},
                    {"INDUSTRY", "RETAIL"}
                }) {
            jdbc.update(
                    """
                    INSERT INTO "CATALOG"."TAXONOMY_CODES" ("KIND","CODE","LABEL_VI","TAXONOMY_VERSION")
                    VALUES (?,?,'Nhãn kiểm thử','cv-taxonomy/0') ON CONFLICT DO NOTHING
                    """,
                    code[0],
                    code[1]);
        }
    }

    @Test
    void rankingKeepsTiersDisjointAndPrefersCurrentPositionsWithinEachTier() throws Exception {
        String marker = unique("rankmarker");
        String companyName = unique("Công ty Hư Cấu");
        String titleName = unique("Chức danh Hư Cấu");
        long company = company(companyName, null, "USER");
        long child = company(unique("Chi nhánh Hư Cấu"), company, "AI");
        long title = title(titleName);
        String text = marker + " " + companyName + " " + titleName;
        long directCurrent = candidate(text, company, null, title, company, title, "Hà Nội", 35);
        long directOld = candidate(text, company, null, title, null, null, "Hà Nội", 35);
        long parentCurrent = candidate(text, child, company, title, child, title, "Hà Nội", 35);
        long parentOld = candidate(text, child, company, title, null, null, "Hà Nội", 35);
        long titleCurrent = candidate(text, null, null, title, null, title, "Hà Nội", 35);
        long titleOld = candidate(text, null, null, title, null, null, "Hà Nội", 35);
        long textOnly = candidate(text, null, null, null, null, null, "Hà Nội", 35);
        var response =
                search(
                        "q="
                                + encode(marker)
                                + "&company="
                                + encode(companyName)
                                + "&title="
                                + encode(titleName));
        assertThat(resultIds(response))
                .containsExactly(
                        directCurrent,
                        directOld,
                        parentCurrent,
                        parentOld,
                        titleCurrent,
                        titleOld,
                        textOnly);
        assertThat(reasons(response.path("content").get(0)))
                .containsExactly(
                        "COMPANY_DIRECT",
                        "COMPANY_TEXT",
                        "COMPANY_CURRENT",
                        "TITLE_CATALOG",
                        "TITLE_TEXT",
                        "TITLE_CURRENT",
                        "KEYWORD_TEXT",
                        "KEYWORD_FUZZY");
        var parent = response.path("content").get(2);
        assertThat(parent.path("matchedCompany").path("id").asLong()).isEqualTo(company);
        assertThat(parent.path("currentCompany").path("parentName").asString())
                .isEqualTo(companyName);
        assertThat(parent.path("currentCompany").path("aiNormalized").asBoolean()).isTrue();
        assertThat(parent.path("currentTitle").path("aiNormalized").asBoolean()).isTrue();
        for (var row : response.path("content")) {
            assertThat(row.path("experienceEstimated").asBoolean()).isTrue();
            assertThat(row.path("totalExperienceMonths").asInt()).isEqualTo(35);
            assertThat(row.path("score").decimalValue().scale()).isLessThanOrEqualTo(6);
            assertThat(
                            row.has("email")
                                    || row.has("phone")
                                    || row.has("gender")
                                    || row.has("age")
                                    || row.has("dateOfBirth"))
                    .isFalse();
        }
        assertThat(response.path("content").get(6).path("currentCompany").isNull()).isTrue();
        assertThat(response.path("content").get(6).path("matchedCompany").isNull()).isTrue();
    }

    @Test
    void defaultCompanyCurrentBonusWinsWithinTheDirectTier() throws Exception {
        var fixture = positionBonusFixture();
        assertThat(resultIds(search(fixture.query())))
                .containsExactly(fixture.currentCompany(), fixture.currentTitle());
    }

    @Test
    void catalogTitleOutranksTextTitleInBothCompanyTiers() throws Exception {
        String marker = unique("catalogbonusmarker");
        long company = company(unique("Catalog Bonus Company"), null, "USER");
        long child = company(unique("Catalog Bonus Child"), company, "USER");
        String titleName = unique("Catalog Bonus Title");
        long title = title(titleName);
        long directCatalog = candidate(marker, company, null, title, null, null, null, 0);
        long directText =
                candidate(marker + " " + titleName, company, null, null, null, null, null, 0);
        long parentCatalog = candidate(marker, child, company, title, null, null, null, 0);
        long parentText =
                candidate(marker + " " + titleName, child, company, null, null, null, null, 0);
        var result =
                search(
                        "q="
                                + encode(marker)
                                + "&companyId="
                                + company
                                + "&title="
                                + encode(titleName));
        assertThat(resultIds(result))
                .containsExactly(directCatalog, directText, parentCatalog, parentText);
        assertThat(reasons(result.path("content").get(0)))
                .contains("TITLE_CATALOG")
                .doesNotContain("TITLE_TEXT");
        assertThat(reasons(result.path("content").get(1)))
                .contains("TITLE_TEXT")
                .doesNotContain("TITLE_CATALOG");
        assertThat(result.path("content").get(0).path("score").decimalValue())
                .isGreaterThan(result.path("content").get(1).path("score").decimalValue());
        assertThat(result.path("content").get(2).path("score").decimalValue())
                .isGreaterThan(result.path("content").get(3).path("score").decimalValue());
    }

    @Test
    void exactNamesAndDoneAliasesDoNotResolveSimilarCatalogEntries() throws Exception {
        String marker = unique("exactmarker");
        String companyName = unique("Exact Company");
        long company = company(companyName, null, "USER");
        long similarCompany = company(companyName + " Labs", null, "USER");
        String titleName = unique("Exact Title");
        long title = title(titleName);
        long similarTitle = title(titleName + " Labs");
        String companyAlias = companyName + " Lab";
        String titleAlias = titleName + " Lab";
        alias("COMPANY", companyAlias, company, "DONE");
        alias("TITLE", titleAlias, title, "DONE");
        long exact = candidate(marker, company, null, title, null, null, null, 0);
        candidate(marker, similarCompany, null, similarTitle, null, null, null, 0);
        for (String filter : List.of(companyName, companyAlias)) {
            var result = search("q=" + encode(marker) + "&company=" + encode(filter));
            assertThat(resultIds(result)).containsExactly(exact);
            assertThat(reasons(result.path("content").get(0))).contains("COMPANY_DIRECT");
        }
        for (String filter : List.of(titleName, titleAlias)) {
            assertThat(resultIds(search("q=" + encode(marker) + "&title=" + encode(filter))))
                    .containsExactly(exact);
        }
        assertThat(resultIds(search("q=" + encode(marker) + "&companyId=" + Long.MAX_VALUE)))
                .isEmpty();
    }

    @Test
    void fuzzyResolutionIsBoundedAndOrderedBySimilarity() {
        String query = unique("Bounded Resolve");
        var inserted = new ArrayList<Long>();
        for (int i = 1; i <= 8; i++)
            inserted.add(company(query + " " + "variants".substring(0, i), null, "USER"));
        var ids =
                searchRepository.resolve(
                        "COMPANY",
                        com.cvcompass.backend.catalog.api.CatalogText.fold(query),
                        searchProperties.getQuery());
        assertThat(ids)
                .hasSize(searchProperties.getQuery().getResolveLimit())
                .doesNotHaveDuplicates();
        assertThat(inserted).containsAll(ids);
        var similarities =
                ids.stream()
                        .map(
                                id ->
                                        jdbc.queryForObject(
                                                "SELECT similarity(\"NAME_FOLDED\",?) FROM \"CATALOG\".\"ENTRIES\" WHERE \"ID\"=?",
                                                Double.class,
                                                com.cvcompass.backend.catalog.api.CatalogText.fold(
                                                        query),
                                                id))
                        .toList();
        assertThat(similarities).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void companyAndTitleTextTreatWildcardAndEscapeCharactersLiterally() throws Exception {
        String marker = unique("literalmarker");
        String company = unique("Literal Company") + "%_branch\\office";
        String title = unique("Literal Title") + "%_role\\desk";
        long literal =
                candidate(
                        marker + " " + company + " " + title,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0);
        for (String special : List.of("%", "_", "\\")) {
            candidate(
                    marker
                            + " "
                            + company.replace(special, "x")
                            + " "
                            + title.replace(special, "x"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0);
        }
        assertThat(resultIds(search("q=" + encode(marker) + "&company=" + encode(company))))
                .containsExactly(literal);
        assertThat(resultIds(search("q=" + encode(marker) + "&title=" + encode(title))))
                .containsExactly(literal);
        var both =
                search(
                        "q="
                                + encode(marker)
                                + "&company="
                                + encode(company)
                                + "&title="
                                + encode(title));
        assertThat(resultIds(both)).containsExactly(literal);
        assertThat(reasons(both.path("content").get(0))).contains("COMPANY_TEXT", "TITLE_TEXT");
    }

    @Test
    void companyAndTitleResolveIdsFoldedNamesAliasesAndTypos() throws Exception {
        String marker = unique("filtermarker");
        String name = unique("Điện Quang Hư Cấu");
        long company = company(name, null, "USER");
        long child = company(unique("Chi nhánh"), company, "AI");
        String titleName = unique("Quản lý Hư Cấu");
        long title = title(titleName);
        String companyAlias = unique("zzaliascompany");
        String titleAlias = unique("zzaliastitle");
        alias("COMPANY", companyAlias, company, "DONE");
        alias("TITLE", titleAlias, title, "DONE");
        long direct = candidate(marker, company, null, title, null, null, null, null);
        long parent = candidate(marker, child, company, title, child, title, null, null);
        for (String filter :
                List.of(
                        name,
                        com.cvcompass.backend.catalog.api.CatalogText.fold(name),
                        companyAlias,
                        name.replace("Quang", "Quagn"))) {
            assertThat(resultIds(search("q=" + encode(marker) + "&company=" + encode(filter))))
                    .containsExactlyInAnyOrder(direct, parent);
        }
        assertThat(resultIds(search("q=" + encode(marker) + "&companyId=" + company)))
                .containsExactlyInAnyOrder(direct, parent);
        for (String filter :
                List.of(
                        String.valueOf(title),
                        titleName,
                        titleAlias,
                        titleName.replace("Quản", "Quanr"))) {
            assertThat(resultIds(search("q=" + encode(marker) + "&title=" + encode(filter))))
                    .containsExactlyInAnyOrder(direct, parent);
        }
        String pending = unique("zzpending");
        alias("COMPANY", pending, null, "PENDING");
        assertThat(resultIds(search("q=" + encode(marker) + "&company=" + encode(pending))))
                .isEmpty();
        long textOnly =
                candidate(
                        marker + " Unlinked Employer Unlinked Position",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertThat(
                        resultIds(
                                search(
                                        "q="
                                                + encode(marker)
                                                + "&company=Unlinked%20Employer&title=Unlinked%20Position")))
                .containsExactly(textOnly);
    }

    @Test
    void numericCompanyNamesAndDoneAliasesResolveCatalogAndText() throws Exception {
        String marker = unique("numericcompanymarker").replaceAll("[0-9]", "x");
        long named = company("24", null, "USER");
        long aliased = company("1080 Synthetic", null, "USER");
        long similar = company("1080 Synthetic Labs", null, "USER");
        alias("COMPANY", "1080", aliased, "DONE");
        long nameMatch = candidate(marker, named, null, null, null, null, null, 0);
        long aliasMatch = candidate(marker, aliased, null, null, null, null, null, 0);
        candidate(marker, similar, null, null, null, null, null, 0);
        long textOnly = candidate(marker + " 24 1080", null, null, null, null, null, null, 0);
        for (String filter : List.of("24", "1080")) {
            var result = search("q=" + encode(marker) + "&company=" + filter);
            assertThat(resultIds(result))
                    .containsExactly(filter.equals("24") ? nameMatch : aliasMatch, textOnly);
            assertThat(reasons(result.path("content").get(0))).contains("COMPANY_DIRECT");
            assertThat(result.path("content").get(0).path("matchedCompany").path("id").asLong())
                    .isEqualTo(filter.equals("24") ? named : aliased);
            assertThat(reasons(result.path("content").get(1)))
                    .contains("COMPANY_TEXT")
                    .doesNotContain("COMPANY_DIRECT", "COMPANY_PARENT");
            assertThat(result.path("content").get(1).path("matchedCompany").isNull()).isTrue();
        }
        String longName = "9223372036854775808";
        long longText = candidate(marker + " " + longName, null, null, null, null, null, null, 0);
        assertThat(resultIds(search("q=" + encode(marker) + "&company=" + longName)))
                .containsExactly(longText);
    }

    @Test
    void numericCompanyNamesUseBoundedTrigramFallback() throws Exception {
        String marker = unique("numericfuzzymarker");
        String query = "9753108642";
        for (int i = 0; i < 8; i++) {
            long company = company(query + i, null, "USER");
            candidate(marker, company, null, null, null, null, null, 0);
        }
        assertThat(resultIds(search("q=" + encode(marker) + "&company=" + query)))
                .hasSize(searchProperties.getQuery().getResolveLimit());
    }

    @Test
    void numericCompanyTextDoesNotMatchAnExistingCatalogId() throws Exception {
        String marker = unique("numericidmarker").replaceAll("[0-9]", "x");
        String name = unique("Synthetic Alphabetic Company").replaceAll("[0-9]", "x");
        long company = company(name, null, "USER");
        alias(
                "COMPANY",
                unique("Synthetic Alphabetic Alias").replaceAll("[0-9]", "x"),
                company,
                "DONE");
        long linked = candidate(marker, company, null, null, null, null, null, 0);

        assertThat(resultIds(search("q=" + encode(marker) + "&company=" + company))).isEmpty();
        var byId = search("q=" + encode(marker) + "&companyId=" + company);
        assertThat(resultIds(byId)).containsExactly(linked);
        assertThat(reasons(byId.path("content").get(0)))
                .contains("COMPANY_DIRECT")
                .doesNotContain("COMPANY_PARENT", "COMPANY_TEXT");
    }

    @Test
    void companyIdPreservesCatalogRankingAndNeverMatchesText() throws Exception {
        String marker = unique("companyidmarker");
        long company = company(unique("Company Id Synthetic"), null, "USER");
        long child = company(unique("Company Id Child"), company, "USER");
        String text = marker + " " + company + " " + Long.MAX_VALUE;
        long directCurrent = candidate(text, company, null, null, company, null, null, 0);
        long directOld = candidate(text, company, null, null, null, null, null, 0);
        long parentCurrent = candidate(text, child, company, null, child, null, null, 0);
        long parentOld = candidate(text, child, company, null, null, null, null, 0);
        candidate(text, null, null, null, null, null, null, 0);
        var result = search("q=" + encode(marker) + "&companyId=" + company);
        assertThat(resultIds(result))
                .containsExactly(directCurrent, directOld, parentCurrent, parentOld);
        var content = result.path("content");
        assertThat(reasons(content.get(0)))
                .contains("COMPANY_DIRECT", "COMPANY_CURRENT")
                .doesNotContain("COMPANY_PARENT", "COMPANY_TEXT");
        assertThat(reasons(content.get(1)))
                .contains("COMPANY_DIRECT")
                .doesNotContain("COMPANY_CURRENT", "COMPANY_TEXT");
        assertThat(reasons(content.get(2)))
                .contains("COMPANY_PARENT", "COMPANY_CURRENT")
                .doesNotContain("COMPANY_DIRECT", "COMPANY_TEXT");
        assertThat(reasons(content.get(3)))
                .contains("COMPANY_PARENT")
                .doesNotContain("COMPANY_CURRENT", "COMPANY_TEXT");
        for (var row : content) {
            assertThat(row.path("matchedCompany").path("id").asLong()).isEqualTo(company);
        }
        assertThat(resultIds(search("q=" + encode(marker) + "&companyId=" + Long.MAX_VALUE)))
                .isEmpty();
    }

    @Test
    void taxonomyProvinceAndInclusiveFlooredYearsComposeWithAndAndOr() throws Exception {
        String marker = unique("yearsmarker");
        long first = candidate(marker, null, null, null, null, null, "Hà Nội", 24);
        long second = candidate(marker, null, null, null, null, null, "Đà Nẵng", 35);
        candidate(marker, null, null, null, null, null, "Hà Nội", 36);
        candidate(marker, null, null, null, null, null, "Hà Nội", 23);
        candidate(marker, null, null, null, null, null, "Hà Nội", null);
        String base = "q=" + encode(marker) + "&minYears=2&maxYears=2";
        for (String filter :
                List.of(
                        "function=IT",
                        "function=SALES&function=IT",
                        "seniority=MANAGER",
                        "scope=COMPANY",
                        "industry=LOGISTICS")) {
            assertThat(resultIds(search(base + "&" + filter)))
                    .containsExactlyInAnyOrder(first, second);
        }
        assertThat(resultIds(search(base + "&province=" + encode("Hà Nội"))))
                .containsExactly(first);
        assertThat(
                        resultIds(
                                search(
                                        base
                                                + "&province="
                                                + encode("Hà Nội")
                                                + "&province="
                                                + encode("Đà Nẵng"))))
                .containsExactlyInAnyOrder(first, second);
        assertThat(resultIds(search(base + "&function=SALES"))).isEmpty();
        assertThat(resultIds(search(base + "&function=IT&industry=RETAIL"))).isEmpty();
    }

    @Test
    void keywordsUseFullTextAndWordTrigramsForSyntheticNames() throws Exception {
        String text = unique("Synthetic zzcandidatequang");
        long id = candidate(text, null, null, null, null, null, null, 0);
        var exact = search("q=" + encode(text));
        assertThat(resultIds(exact)).containsExactly(id);
        assertThat(reasons(exact.path("content").get(0))).contains("KEYWORD_TEXT");
        var fuzzy = search("q=zzcandidatequagn");
        assertThat(resultIds(fuzzy)).contains(id);
        assertThat(reasons(fuzzy.path("content").get(0)))
                .contains("KEYWORD_FUZZY")
                .doesNotContain("KEYWORD_TEXT");
    }

    @Test
    void keysetWalksTiesAndDifferentScoresWithoutDuplicatesOrGaps() throws Exception {
        String marker = unique("pagemarker");
        String name = unique("Paging Company");
        long company = company(name, null, "USER");
        for (int i = 0; i < 9; i++)
            candidate(
                    marker + " " + name,
                    i % 2 == 0 ? company : null,
                    null,
                    null,
                    i % 3 == 0 ? company : null,
                    null,
                    null,
                    null);
        String query = "q=" + encode(marker) + "&company=" + encode(name);
        var expected = resultIds(search(query + "&limit=50"));
        var seen = new ArrayList<Long>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            var response = search(query + "&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(resultIds(response));
            if (!response.path("hasNext").asBoolean()) {
                assertThat(response.path("nextCursor").isNull()).isTrue();
                break;
            }
            cursor = response.path("nextCursor").asString();
            assertThat(cursor).isNotBlank();
        }
        assertThat(seen).doesNotHaveDuplicates().containsExactlyElementsOf(expected).hasSize(9);
    }

    @Test
    void keysetPreservesFractionalFullTextScoresAcrossManyPages() throws Exception {
        String marker = unique("fractionalrank");
        for (int i = 1; i <= 12; i++) {
            candidate((marker + " ").repeat(i), null, null, null, null, null, null, 0);
        }
        String query = "q=" + encode(marker);
        var all = search(query + "&limit=50");
        var scores = new java.util.HashSet<java.math.BigDecimal>();
        for (var row : all.path("content")) {
            var score = row.path("score").decimalValue();
            assertThat(score.remainder(java.math.BigDecimal.ONE)).isPositive();
            scores.add(score);
        }
        assertThat(scores.size()).isGreaterThan(1);
        var seen = new ArrayList<Long>();
        String cursor = null;
        for (int page = 0; page < 12; page++) {
            var result = search(query + "&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(resultIds(result));
            if (!result.path("hasNext").asBoolean()) {
                assertThat(result.path("nextCursor").isNull()).isTrue();
                break;
            }
            cursor = result.path("nextCursor").asString();
        }
        assertThat(seen)
                .hasSize(12)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(resultIds(all));
    }

    @Test
    void invalidParametersReturnSafeFieldViolations() throws Exception {
        jdbc.update(
                """
                INSERT INTO "CATALOG"."TAXONOMY_CODES" ("KIND","CODE","LABEL_VI","TAXONOMY_VERSION","ACTIVE")
                VALUES ('FUNCTION','TEST_INACTIVE','Nhãn tắt','cv-taxonomy/0',false) ON CONFLICT DO NOTHING
                """);
        for (String[] input :
                new String[][] {
                    {"cursor=corrupt", "cursor"},
                    {"limit=51", "limit"},
                    {"limit=0", "limit"},
                    {"limit=abc", "limit"},
                    {"companyId=abc", "companyId"},
                    {"companyId=0", "companyId"},
                    {"companyId=-1", "companyId"},
                    {"companyId=1.5", "companyId"},
                    {"companyId=", "companyId"},
                    {"companyId=9223372036854775808", "companyId"},
                    {"companyId=1&companyId=1", "companyId"},
                    {"companyId=1&companyId=2", "companyId"},
                    {"company=Synthetic&companyId=1", "companyId"},
                    {"company=&companyId=1", "companyId"},
                    {"function=INVALID", "function"},
                    {"function=MANAGER", "function"},
                    {"function=TEST_INACTIVE", "function"},
                    {
                        "function=INVALID&seniority=INVALID&scope=INVALID&industry=INVALID",
                        "function"
                    },
                    {"function=IT&seniority=INVALID&scope=INVALID&industry=INVALID", "seniority"},
                    {"function=IT&seniority=MANAGER&scope=INVALID&industry=INVALID", "scope"},
                    {"seniority=IT", "seniority"},
                    {"scope=IT", "scope"},
                    {"industry=IT", "industry"},
                    {"minYears=-1", "minYears"},
                    {"maxYears=-1", "maxYears"},
                    {"minYears=3&maxYears=2", "maxYears"},
                    {"minYears=2.5", "minYears"}
                }) {
            var response = get("/api/candidates/search?" + input[0]);
            assertThat(response.statusCode()).as(input[0]).isEqualTo(400);
            var error = json.readTree(response.body());
            assertThat(error.path("code").asString()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.path("details").get(0).path("field").asString()).isEqualTo(input[1]);
            if (input[0].startsWith("company=")) {
                assertThat(error.path("details").get(0).path("message").asString())
                        .contains("only one of company and companyId");
            }
            assertThat(error.path("correlationId").asString()).isNotBlank();
            assertThat(response.body()).doesNotContain("corrupt", "INVALID", "TEST_INACTIVE");
        }
    }

    @Test
    void optionsSuggestionsAndExistingRoutesWorkForAnOrdinaryUser() throws Exception {
        String name = unique("Mẫu Công ty");
        long parent = company(unique("Tập đoàn Mẫu"), null, "USER");
        long company = company(name, parent, "AI");
        String alias = unique("zzsuggestalias");
        alias("COMPANY", alias, company, "DONE");
        long id =
                candidate(
                        unique("routesmarker"), company, parent, null, company, null, "Hà Nội", 12);
        var options = get("/api/candidates/search/options");
        assertThat(options.statusCode()).isEqualTo(200);
        var body = json.readTree(options.body());
        for (String kind : List.of("FUNCTION", "SENIORITY", "SCOPE", "INDUSTRY"))
            assertThat(body.path(kind).isArray()).isTrue();
        assertThat(body.path("FUNCTION").toString())
                .contains("IT", "label")
                .doesNotContain("TEST_INACTIVE");
        var provinces = new ArrayList<String>();
        body.path("provinces").forEach(value -> provinces.add(value.asString()));
        assertThat(provinces).contains("Hà Nội").doesNotHaveDuplicates();
        for (String q : List.of(name, alias, name.replace("Công", "Cong"))) {
            var suggestions = get("/api/candidates/search/companies?q=" + encode(q));
            assertThat(suggestions.statusCode()).isEqualTo(200);
            var entries = json.readTree(suggestions.body());
            assertThat(entries.size()).isLessThanOrEqualTo(10);
            assertThat(entries.toString()).contains("\"id\":" + company, "parentName");
        }
        assertThat(get("/api/candidates/" + id).statusCode()).isEqualTo(200);
        assertThat(get("/api/candidates").statusCode()).isEqualTo(200);
        for (String suffix : List.of("", "/options", "/companies?q=synthetic")) {
            var anonymous =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/candidates/search"
                                                            + suffix))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void oneCharacterSuggestionsUsePrefixMatchingAndRemainBounded() throws Exception {
        long exact = company("X", null, "USER");
        company(unique("X synthetic suggestion"), null, "USER");
        var response = get("/api/candidates/search/companies?q=x");
        assertThat(response.statusCode()).isEqualTo(200);
        var results = json.readTree(response.body());
        assertThat(results.size()).isBetween(2, searchProperties.getQuery().getSuggestLimit());
        assertThat(results.get(0).path("id").asLong()).isEqualTo(exact);
        assertThat(get("/api/candidates/search/companies?q=x").body()).isEqualTo(response.body());
    }

    private List<String> reasons(JsonNode row) {
        var result = new ArrayList<String>();
        row.path("matchReasons").forEach(value -> result.add(value.asString()));
        return result;
    }
}
