package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;

import com.cvcompass.backend.mapping.config.RemapProperties;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.RemapRequest;
import com.cvcompass.backend.mapping.dto.RemapRun;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import com.cvcompass.backend.mapping.service.MappingService;
import com.cvcompass.backend.mapping.service.RemapService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"cv.mapping.enabled=false", "cv.candidate.publication.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class RemapIntegrationTests {
    private static final IsolatedPostgresSchema DATABASE = new IsolatedPostgresSchema();
    private static final String ENGINE = "personal-info-mapping/3.5";
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MappingService mapping;
    @Autowired MappingRepository jobs;
    @Autowired com.cvcompass.backend.mapping.repository.MappingRemoteRepository remoteJobs;
    @Autowired RemapService remaps;
    @Autowired RemapProperties properties;
    @Autowired PlatformTransactionManager transactions;
    private long config;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        new TestContextManager(RemapIntegrationTests.class)
                .getTestContext()
                .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        DATABASE.close();
    }

    @BeforeEach
    void configure() throws Exception {
        properties.setBatchSize(2);
        properties.setMaxPending(2);
        properties.setListLimit(2);
        config = config();
        mapping.activate(config);
    }

    @Test
    void boundedBatchesPauseResumePinConfigurationAndSkipDuplicates() {
        long first = extraction(null, "2026-11-01T00:00:00Z");
        long second = extraction(null, "2026-11-01T00:00:00Z");
        long third = extraction(null, "2026-11-01T00:00:00Z");
        var run = remaps.create(scope("ALL"), "synthetic-admin");
        long later = extraction(null, "2026-11-01T00:00:00Z");
        mapping.activate(config());
        remaps.advance(run.id());
        var page = remaps.get(run.id());
        assertThat(page.upperExtractionId()).isEqualTo(third);
        assertThat(page.cursorExtractionId()).isEqualTo(second);
        assertThat(page.configId()).isEqualTo(config);
        assertThat(page.selected()).isEqualTo(2);
        assertThat(page.queued()).isEqualTo(2);
        assertThat(page.pending()).isEqualTo(2);
        assertThat(page.state()).isEqualTo("RUNNING");
        remaps.advance(run.id());
        assertThat(remaps.get(run.id())).isEqualTo(page);
        remaps.pause(run.id());
        terminal(first, "FAILED");
        terminal(second, "COMPLETED");
        remaps.advance(run.id());
        assertThat(remaps.get(run.id()).cursorExtractionId()).isEqualTo(second);
        assertThat(remaps.get(run.id()).failed()).isEqualTo(1);
        assertThat(remaps.get(run.id()).completed()).isEqualTo(1);
        remaps.resume(run.id());
        remaps.advance(run.id());
        var done = remaps.get(run.id());
        assertThat(done.state()).isEqualTo("DONE");
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.selected()).isEqualTo(3);
        assertThat(done.queued()).isEqualTo(3);
        assertThat(done.pending()).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT \"EXTRACTION_ID\" FROM \"MAPPING\".\"JOBS\" WHERE \"REMAP_RUN_ID\"=? ORDER BY \"ID\"",
                                Long.class,
                                run.id()))
                .containsExactly(first, second, third)
                .doesNotContain(later);
        assertThat(remaps.pause(run.id()).state()).isEqualTo("DONE");
        assertThat(remaps.resume(run.id()).state()).isEqualTo("DONE");
        mapping.activate(config);
        // A repeat over the same original range only skips; a later extraction remains eligible.
        var repeat = remaps.create(scope("ALL"), "synthetic-admin");
        remaps.advance(repeat.id());
        remaps.advance(repeat.id());
        assertThat(remaps.get(repeat.id()).skipped()).isEqualTo(3);
        assertThat(remaps.get(repeat.id()).queued()).isEqualTo(1);
        assertThat(remaps.recent()).hasSize(2);
    }

    @Test
    void newCvJobsAreClaimedBeforeOlderRemapJobs() {
        long old = extraction(null, "2026-11-01T00:00:00Z");
        var run = remaps.create(scope("ALL"), "synthetic-admin");
        remaps.advance(run.id());
        long fresh = extraction(null, "2026-11-02T00:00:00Z");
        jobs.enqueue(fresh, config, ENGINE, 3);
        assertThat(mapping.claim().orElseThrow().extractionId()).isEqualTo(fresh);
        var remap = mapping.claim().orElseThrow();
        assertThat(remap.extractionId()).isEqualTo(old);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"PRIORITY\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                Integer.class,
                                remap.jobId()))
                .isEqualTo(10);
    }

    @Test
    void remoteClaimAlsoSelectsFreshJobsBeforeRemaps() {
        var definition = com.cvcompass.backend.mapping.service.MappingRemoteFixtures.definition();
        long remoteConfig = mapping.register(definition.revision(), definition);
        long old = extraction(null, "2026-11-01T00:00:00Z");
        long fresh = extraction(null, "2026-11-02T00:00:00Z");
        jobs.enqueue(old, remoteConfig, "llm-extraction/1.0", 3, 10, null);
        jobs.enqueue(fresh, remoteConfig, "llm-extraction/1.0", 3);
        long selected = remoteJobs.ready().orElseThrow();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"EXTRACTION_ID\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                Long.class,
                                selected))
                .isEqualTo(fresh);
        terminal(fresh, "FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT \"EXTRACTION_ID\" FROM \"MAPPING\".\"JOBS\" WHERE \"ID\"=?",
                                Long.class,
                                remoteJobs.ready().orElseThrow()))
                .isEqualTo(old);
    }

    @Test
    void batchAndInclusiveLocalDateScopesOnlySelectMatchingDocuments() {
        long batch =
                jdbc.queryForObject(
                        "INSERT INTO \"INGESTION\".\"IMPORT_BATCHES\" (\"TOTAL_FILES\",\"STATUS\") VALUES (2,'PENDING') RETURNING \"ID\"",
                        Long.class);
        long before = extraction(batch, "2026-11-23T16:59:59Z");
        long start = extraction(batch, "2026-11-23T17:00:00Z");
        long end = extraction(null, "2026-11-24T16:59:59Z");
        long after = extraction(null, "2026-11-24T17:00:00Z");
        properties.setMaxPending(10);
        properties.setBatchSize(10);
        var selectedBatch =
                remaps.create(
                        new RemapRequest("BATCH", batch, null, null, null), "synthetic-admin");
        remaps.advance(selectedBatch.id());
        assertThat(selected(selectedBatch)).containsExactly(before, start);
        var dates =
                remaps.create(
                        new RemapRequest(
                                "DATE_RANGE",
                                null,
                                LocalDate.parse("2026-11-24"),
                                LocalDate.parse("2026-11-24"),
                                null),
                        "synthetic-admin");
        remaps.advance(dates.id());
        assertThat(remaps.get(dates.id()).selected()).isEqualTo(2);
        assertThat(remaps.get(dates.id()).skipped()).isEqualTo(1);
        assertThat(selected(dates)).containsExactly(end).doesNotContain(before, after);
    }

    @Test
    void issueScopeUsesLatestResultAndNestedIssueArraysRatherThanText() {
        long nested = extraction(null, "2026-11-01T00:00:00Z");
        long cleared = extraction(null, "2026-11-01T00:00:00Z");
        long textOnly = extraction(null, "2026-11-01T00:00:00Z");
        issueResult(
                nested,
                config,
                "{\"personalInfo\":{\"phone\":{\"issues\":[{\"code\":\"INVALID_PHONE\"}]}}}");
        issueResult(cleared, config, "{\"issues\":[{\"code\":\"INVALID_PHONE\"}]}");
        issueResult(cleared, config(), "{\"issues\":[]}");
        issueResult(textOnly, config, "{\"text\":\"INVALID_PHONE\",\"issues\":[]}");
        mapping.activate(config());
        var run =
                remaps.create(
                        new RemapRequest("ISSUE", null, null, null, "INVALID_PHONE"),
                        "synthetic-admin");
        remaps.advance(run.id());
        assertThat(selected(run)).containsExactly(nested);
        assertThat(remaps.get(run.id()).selected()).isEqualTo(1);
    }

    @Test
    void failedBatchRollsBackEnqueuesCursorAndCountersTogether() {
        long first = extraction(null, "2026-11-01T00:00:00Z");
        extraction(null, "2026-11-01T00:00:00Z");
        var run = remaps.create(scope("ALL"), "synthetic-admin");
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(
                status -> {
                    remaps.advance(run.id());
                    assertThat(remaps.get(run.id()).queued()).isEqualTo(2);
                    status.setRollbackOnly();
                });
        assertThat(remaps.get(run.id()).selected()).isZero();
        assertThat(remaps.get(run.id()).cursorExtractionId()).isZero();
        assertThat(selected(run)).isEmpty();
        remaps.advance(run.id());
        assertThat(selected(run)).startsWith(first);
        assertThat(remaps.get(run.id()).queued()).isEqualTo(2);
    }

    private RemapRequest scope(String value) {
        return new RemapRequest(value, null, null, null, null);
    }

    private List<Long> selected(RemapRun run) {
        return jdbc.queryForList(
                "SELECT \"EXTRACTION_ID\" FROM \"MAPPING\".\"JOBS\" WHERE \"REMAP_RUN_ID\"=? ORDER BY \"EXTRACTION_ID\"",
                Long.class,
                run.id());
    }

    private void terminal(long extraction, String state) {
        jdbc.update(
                "UPDATE \"MAPPING\".\"JOBS\" SET \"STATUS\"=?,\"COMPLETED_AT\"=clock_timestamp(),\"INPUT_SHA256\"=? WHERE \"EXTRACTION_ID\"=?",
                state,
                "a".repeat(64),
                extraction);
    }

    private long extraction(Long batch, String importedAt) {
        long doc =
                jdbc.queryForObject(
                        """
                INSERT INTO "INGESTION"."DOCUMENTS" ("BATCH_ID","ORIGINAL_FILENAME","STORAGE_PATH","FILE_SIZE","MIME_TYPE","SHA256","STATUS","CREATED_AT")
                VALUES (?,'synthetic.pdf','synthetic.pdf',1,'application/pdf',?,'COMPLETED',?) RETURNING "ID"
                """,
                        Long.class,
                        batch,
                        "a".repeat(64),
                        OffsetDateTime.parse(importedAt));
        long ocrJob =
                jdbc.queryForObject(
                        "INSERT INTO \"INGESTION\".\"PROCESSING_JOBS\" (\"CV_DOCUMENT_ID\",\"STATUS\") VALUES (?,'PENDING') RETURNING \"ID\"",
                        Long.class,
                        doc);
        return jdbc.queryForObject(
                "INSERT INTO \"INGESTION\".\"EXTRACTIONS\" (\"CV_DOCUMENT_ID\",\"PROCESSING_JOB_ID\",\"EXTRACTION_METHOD\") VALUES (?,?,'OCR') RETURNING \"ID\"",
                Long.class,
                doc,
                ocrJob);
    }

    private long config() {
        var tree =
                (ObjectNode)
                        json.readTree(
                                getClass()
                                        .getResourceAsStream(
                                                "/mapping/personal-info-config-v3.5.json"));
        tree.put("revision", "synthetic-remap-" + UUID.randomUUID());
        var definition = json.treeToValue(tree, PersonalInfoDefinition.class);
        return mapping.register(definition.revision(), definition);
    }

    private void issueResult(long extraction, long configuration, String body) {
        jobs.enqueue(extraction, configuration, ENGINE, 3);
        jdbc.update(
                """
                INSERT INTO "MAPPING"."RESULTS" ("JOB_ID","SCHEMA_VERSION","QUALITY","DIGEST","RESULT")
                SELECT "ID",3,'NEEDS_REVIEW',?,?::jsonb FROM "MAPPING"."JOBS" WHERE "EXTRACTION_ID"=? AND "CONFIG_ID"=?
                """,
                "b".repeat(64),
                body,
                extraction,
                configuration);
    }
}
