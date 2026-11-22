package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.candidate.events.CandidateChanged;
import com.cvcompass.backend.candidate.service.CandidateExperienceService;
import com.cvcompass.backend.candidate.service.CandidateService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "cv.candidate.publication.enabled=false")
@Import(CatalogLinkInvalidationIntegrationTests.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogLinkInvalidationIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CandidateService candidates;
    @Autowired CandidateExperienceService experiences;
    @Autowired CatalogLinks links;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ChangeProbe probe;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new org.springframework.test.context.TestContextManager(
                        CatalogLinkInvalidationIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @Test
    void hrRenameClearsLinksButAnUnchangedNamePreservesThem() {
        long id = candidate();
        var initial = candidates.editExperience(id, 0, null, data(), "SAVE", "synthetic-editor");
        UUID experienceId = initial.experiences().getFirst().id();
        normalize(id, experienceId);
        var sameNames = data();
        sameNames.put("descriptionRaw", "Synthetic changed description");
        var descriptionEdit =
                candidates.editExperience(
                        id, initial.version(), experienceId, sameNames, "SAVE", "synthetic-editor");
        assertLinked(links.experiences(id).getFirst());

        var renamed = data();
        ((ObjectNode) renamed.path("title")).put("raw", "Synthetic changed title");
        candidates.editExperience(
                id, descriptionEdit.version(), experienceId, renamed, "SAVE", "synthetic-editor");
        assertCleared(links.experiences(id).getFirst());
        assertThat(links.experiences(id).getFirst().titleRaw())
                .isEqualTo("Synthetic changed title");
    }

    @Test
    void userLinksSurviveAnHrRename() {
        long id = candidate();
        var initial = candidates.editExperience(id, 0, null, data(), "SAVE", "synthetic-editor");
        UUID experienceId = initial.experiences().getFirst().id();
        normalize(id, experienceId, "USER");
        var renamed = data();
        ((ObjectNode) renamed.path("title")).put("raw", "Synthetic user-protected rename");
        ((ObjectNode) renamed.path("company")).put("raw", "Synthetic user-protected employer");
        candidates.editExperience(
                id, initial.version(), experienceId, renamed, "SAVE", "synthetic-editor");
        var row = links.experiences(id).getFirst();
        assertThat(row.titleRaw()).isEqualTo("Synthetic user-protected rename");
        assertThat(row.companyId()).isNotNull();
        assertThat(row.titleId()).isNotNull();
        assertThat(row.scopeCode()).isEqualTo("COMPANY");
        assertThat(row.normalizationOrigin()).isEqualTo("USER");
    }

    @Test
    void codeEditWithUnchangedNamesKeepsLinksButWritesTheEditedCodes() {
        long id = candidate();
        var initial = candidates.editExperience(id, 0, null, data(), "SAVE", "synthetic-editor");
        UUID experienceId = initial.experiences().getFirst().id();
        normalize(id, experienceId, "AI");
        var edited = data();
        ((ObjectNode) edited.path("title")).set("functionCodes", json.readTree("[\"HR\",\"IT\"]"));
        ((ObjectNode) edited.path("title")).put("seniorityCode", "MANAGER");
        ((ObjectNode) edited.path("company")).set("industryCodes", json.readTree("[\"RETAIL\"]"));
        candidates.editExperience(
                id, initial.version(), experienceId, edited, "SAVE", "synthetic-editor");
        var row = links.experiences(id).getFirst();
        assertThat(row.companyId()).isNotNull();
        assertThat(row.titleId()).isNotNull();
        assertThat(row.normalizationOrigin()).isEqualTo("AI");
        assertThat(json.readTree(row.functionCodes()).toString()).isEqualTo("[\"HR\",\"IT\"]");
        assertThat(json.readTree(row.industryCodes()).toString()).isEqualTo("[\"RETAIL\"]");
        assertThat(row.seniorityCode()).isEqualTo("MANAGER");
    }

    @Test
    void remapChangingRawCompanyClearsTheSameLinks() {
        long id = candidate();
        var initial = candidates.editExperience(id, 0, null, data(), "SAVE", "synthetic-editor");
        UUID experienceId = initial.experiences().getFirst().id();
        normalize(id, experienceId);
        var remapped = data();
        ((ObjectNode) remapped.path("company")).put("raw", "Synthetic remapped employer");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                experiences.updateExperience(
                                        experienceId,
                                        remapped,
                                        "CONFIRMED",
                                        "MAPPING",
                                        null,
                                        null));
        assertCleared(links.experiences(id).getFirst());
        assertThat(links.experiences(id).getFirst().companyRaw())
                .isEqualTo("Synthetic remapped employer");
    }

    @Test
    void basicChangeIsVisibleToSynchronousListenersAndRollsBackWithTheCaller() {
        long id = candidate();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            candidates.basic(
                                    id,
                                    0,
                                    json.readTree("{\"fullName\":\"Synthetic event example\"}"),
                                    false,
                                    "synthetic-editor");
                            assertThat(probe.lastCandidate).isEqualTo(id);
                            assertThat(probe.activeTransaction).isTrue();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT \"FULL_NAME\" FROM \"CANDIDATE\".\"PROFILES\" WHERE \"CANDIDATE_ID\"=?",
                                                    String.class,
                                                    id))
                                    .isEqualTo("Synthetic event example");
                            status.setRollbackOnly();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"VERSION\" FROM \"CANDIDATE\".\"PROFILES\" WHERE \"CANDIDATE_ID\"=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"FULL_NAME\" FROM \"CANDIDATE\".\"PROFILES\" WHERE \"CANDIDATE_ID\"=?",
                                String.class,
                                id))
                .isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ChangeProbe changeProbe() {
            return new ChangeProbe();
        }
    }

    static class ChangeProbe {
        long lastCandidate;
        boolean activeTransaction;

        @EventListener
        public void changed(CandidateChanged event) {
            lastCandidate = event.candidateId();
            activeTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    private long candidate() {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO \"CANDIDATE\".\"CANDIDATES\" DEFAULT VALUES RETURNING \"ID\"",
                        Long.class);
        jdbc.update("INSERT INTO \"CANDIDATE\".\"PROFILES\" (\"CANDIDATE_ID\") VALUES (?)", id);
        return id;
    }

    private ObjectNode data() {
        return (ObjectNode)
                json.readTree(
                        """
                {"company":{"raw":"Synthetic original employer","industryCodes":["RETAIL"]},
                 "title":{"raw":"Synthetic original title","functionCodes":["HR"],"seniorityCode":"STAFF"},
                 "period":{"state":"PROPOSED","start":{"year":2020,"month":1,"precision":"MONTH"},
                 "end":{"year":2021,"month":1,"precision":"MONTH"},"ongoingAsStated":false}}
                """);
    }

    private void normalize(long id, UUID experienceId) {
        normalize(id, experienceId, "AI");
    }

    private void normalize(long id, UUID experienceId, String origin) {
        String suffix = UUID.randomUUID().toString();
        long company =
                jdbc.queryForObject(
                        "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"ORIGIN\") VALUES ('COMPANY',?,?,'AI') RETURNING \"ID\"",
                        Long.class,
                        "Synthetic company " + suffix,
                        "synthetic company " + suffix);
        long title =
                jdbc.queryForObject(
                        "INSERT INTO \"CATALOG\".\"ENTRIES\" (\"KIND\",\"NAME\",\"NAME_FOLDED\",\"FUNCTION_CODES\",\"SENIORITY_CODE\",\"ORIGIN\") VALUES ('TITLE',?,?,'{IT}','SENIOR','AI') RETURNING \"ID\"",
                        Long.class,
                        "Synthetic title " + suffix,
                        "synthetic title " + suffix);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                assertThat(
                                                links.normalize(
                                                        id,
                                                        experienceId,
                                                        new CatalogLinks.Normalization(
                                                                company,
                                                                title,
                                                                "COMPANY",
                                                                origin,
                                                                "Synthetic canonical company",
                                                                List.of("IT"),
                                                                List.of("LOGISTICS"),
                                                                "SENIOR")))
                                        .isTrue());
    }

    private void assertLinked(CatalogLinks.ExperienceLink row) {
        assertThat(row.companyId()).isNotNull();
        assertThat(row.titleId()).isNotNull();
        assertThat(row.scopeCode()).isEqualTo("COMPANY");
        assertThat(row.normalizationOrigin()).isEqualTo("AI");
    }

    private void assertCleared(CatalogLinks.ExperienceLink row) {
        assertThat(row.companyId()).isNull();
        assertThat(row.titleId()).isNull();
        assertThat(row.scopeCode()).isNull();
        assertThat(row.normalizationOrigin()).isNull();
    }
}
