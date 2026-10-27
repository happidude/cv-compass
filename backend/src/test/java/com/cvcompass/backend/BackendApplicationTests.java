package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendApplicationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();

    // This class declares its own method so Spring cannot reuse another class's context.
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void removeIsolatedSchema() throws Exception {
        new org.springframework.test.context.TestContextManager(BackendApplicationTests.class)
                .getTestContext()
                .markApplicationContextDirty(
                        org.springframework.test.annotation.DirtiesContext.HierarchyMode
                                .EXHAUSTIVE);
        DATABASE.close();
    }

    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void contextStartsWithV1ThroughV7AndValidatedMappings() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(DATABASE.schemaName());
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
        assertThat(
                        jdbc.queryForList(
                                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                                String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(entityManager.getMetamodel().getEntities())
                .extracting(e -> e.getName())
                .contains(
                        "CvImportBatch",
                        "Candidate",
                        "CvDocument",
                        "CvProcessingJob",
                        "CvExtraction",
                        "CvImportItem");
    }
}
