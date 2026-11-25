package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cv.candidate.publication.enabled=false",
            "cv.search.nightly-cron=-",
            "cv.search.query.weights.company-current=1",
            "cv.search.query.weights.title-current=60",
            "cv.search.query.default-limit=1",
            "cv.search.query.max-limit=3",
            "cv.search.query.suggest-limit=2",
            "cv.search.query.resolve-limit=2",
            "cv.search.query.keyword-trigram-threshold=1"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateSearchWeightsIntegrationTests extends CandidateSearchTestSupport {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(
                        CandidateSearchWeightsIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Test
    void propertyOverrideReversesBonusesAndControlsAllLimits() throws Exception {
        login();
        var fixture = positionBonusFixture();
        assertThat(resultIds(search(fixture.query() + "&limit=3")))
                .containsExactly(fixture.currentTitle(), fixture.currentCompany());
        var firstPage = search(fixture.query());
        assertThat(resultIds(firstPage)).containsExactly(fixture.currentTitle());
        assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
        assertThat(get("/api/candidates/search?limit=4").statusCode()).isEqualTo(400);
        for (int i = 0; i < 4; i++) company(unique("zzbonussuggestion"), null, "USER");
        var suggestions = get("/api/candidates/search/companies?q=zzbonussuggestion");
        assertThat(suggestions.statusCode()).isEqualTo(200);
        assertThat(json.readTree(suggestions.body()).size()).isEqualTo(2);
    }

    @Test
    void keywordThresholdIsIndependentFromCatalogResolution() throws Exception {
        login();
        String marker = unique("thresholdmarker");
        String name = unique("Independent Threshold Company");
        long company = company(name, null, "USER");
        long id = candidate(marker + " zzthresholdquang", company, null, null, null, null, null, 0);
        assertThat(resultIds(search("q=zzthresholdquagn"))).isEmpty();
        assertThat(resultIds(search("q=" + encode(marker) + "&company=" + encode(name + "x"))))
                .containsExactly(id);
    }

    @Test
    void resolveLimitOverrideBoundsFuzzyCompanyMatches() throws Exception {
        login();
        String marker = unique("resolvelimitmarker");
        String query = unique("Bounded Fuzzy Company");
        for (int i = 0; i < 4; i++) {
            long company = company(query + " variant" + i, null, "USER");
            candidate(marker, company, null, null, null, null, null, 0);
        }
        assertThat(
                        resultIds(
                                search(
                                        "q="
                                                + encode(marker)
                                                + "&company="
                                                + encode(query)
                                                + "&limit=3")))
                .hasSize(2);
    }
}
