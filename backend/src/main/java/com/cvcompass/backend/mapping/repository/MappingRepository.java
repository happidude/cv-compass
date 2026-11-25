package com.cvcompass.backend.mapping.repository;

import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.web.OperationalLog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.event.Level;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** All locks/writes join the service's short transaction. Database time governs ownership. */
@Repository
public class MappingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MappingRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long register(String revision, String definition, String digest) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                "mapping:" + revision);
        var existing =
                jdbc.query(
                        """
                SELECT "ID" FROM "MAPPING"."CONFIGS" WHERE "REVISION"=? AND "DEFINITION"=?::jsonb
                """,
                        (rs, n) -> rs.getLong(1),
                        revision,
                        definition);
        if (!existing.isEmpty()) return existing.getFirst();
        return jdbc.queryForObject(
                """
                INSERT INTO "MAPPING"."CONFIGS" ("REVISION","DEFINITION","CONTENT_SHA256","VALIDATION_STATE")
                VALUES (?,?::jsonb,?,'TESTED') RETURNING "ID"
                """,
                Long.class,
                revision,
                definition,
                digest);
    }

    public String definitionJson(long id) {
        return jdbc.queryForObject(
                """
                SELECT "DEFINITION"::text FROM "MAPPING"."CONFIGS" WHERE "ID"=?
                """,
                String.class,
                id);
    }

    public long[] lockSettings() {
        return jdbc.queryForObject(
                """
                SELECT "ACTIVE_CONFIG_ID","SCAN_AFTER_ID","SCAN_UPPER_ID" FROM "MAPPING"."SETTINGS" WHERE "ID"=1 FOR UPDATE
                """,
                (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
    }

    public void activate(long id, String digest) {
        jdbc.update(
                """
                UPDATE "MAPPING"."CONFIGS" SET "ACTIVATED_AT"=clock_timestamp(),
                "CONTENT_SHA256"=?,"VALIDATION_STATE"='ACTIVE'
                WHERE "ID"=? AND "ACTIVATED_AT" IS NULL
                """,
                digest,
                id);
        jdbc.update(
                """
                UPDATE "MAPPING"."SETTINGS" SET "ACTIVE_CONFIG_ID"=?,"SCAN_AFTER_ID"=0,"SCAN_UPPER_ID"=NULL
                WHERE "ID"=1 AND "ACTIVE_CONFIG_ID" IS DISTINCT FROM ?
                """,
                id,
                id);
    }

    public void cursor(long id, Long upperId) {
        jdbc.update(
                "UPDATE \"MAPPING\".\"SETTINGS\" SET \"SCAN_AFTER_ID\"=?,\"SCAN_UPPER_ID\"=? WHERE \"ID\"=1",
                id,
                upperId);
    }

    public void enqueue(long extractionId, long configId, String engine, int maxAttempts) {
        enqueue(extractionId, configId, engine, maxAttempts, 0, null);
    }

    public boolean enqueue(
            long extractionId,
            long configId,
            String engine,
            int maxAttempts,
            int priority,
            Long remapRunId) {
        if (!com.cvcompass.backend.mapping.dto.MappingVersions.config(
                        definitionJson(configId), json)
                .engineCompatibility()
                .equals(engine)) throw new IllegalStateException("MAPPING_ENGINE_CONFIG_MISMATCH");
        int inserted =
                jdbc.update(
                        """
                INSERT INTO "MAPPING"."JOBS" ("EXTRACTION_ID","CONFIG_ID","ENGINE_VERSION","MAX_ATTEMPTS","PRIORITY","REMAP_RUN_ID")
                VALUES (?,?,?,?,?,?) ON CONFLICT ("EXTRACTION_ID","CONFIG_ID","ENGINE_VERSION") DO NOTHING
                """,
                        extractionId,
                        configId,
                        engine,
                        maxAttempts,
                        priority,
                        remapRunId);
        if (inserted == 1) {
            long jobId =
                    jdbc.queryForObject(
                            "SELECT \"ID\" FROM \"MAPPING\".\"JOBS\" WHERE \"EXTRACTION_ID\"=? AND \"CONFIG_ID\"=? AND \"ENGINE_VERSION\"=?",
                            Long.class,
                            extractionId,
                            configId,
                            engine);
            OperationalLog.committed(
                    Level.INFO,
                    OperationalLog.Event.MAPPING_ENQUEUED,
                    null,
                    Map.of(
                            "documentId", documentId(jobId),
                            "jobId", jobId,
                            "extractionId", extractionId));
        }
        return inserted == 1;
    }

    /** Read only identity metadata for operational logs. */
    public long documentId(long jobId) {
        return jdbc.queryForObject(
                "SELECT e.\"CV_DOCUMENT_ID\" FROM \"MAPPING\".\"JOBS\" j JOIN \"INGESTION\".\"EXTRACTIONS\" e ON e.\"ID\"=j.\"EXTRACTION_ID\" WHERE j.\"ID\"=?",
                Long.class,
                jobId);
    }

    public Optional<MappingClaim> claim(
            String engine, String otherEngine, Duration lease, Duration deadline) {
        return claim(engine, otherEngine, otherEngine, lease, deadline);
    }

    public Optional<MappingClaim> claim(
            String engine,
            String otherEngine,
            String personalEngine,
            Duration lease,
            Duration deadline) {
        return claim(engine, otherEngine, personalEngine, personalEngine, lease, deadline);
    }

    public Optional<MappingClaim> claim(
            String engine,
            String otherEngine,
            String personalEngine,
            String grammarEngine,
            Duration lease,
            Duration deadline) {
        return claim(
                engine, otherEngine, personalEngine, grammarEngine, grammarEngine, lease, deadline);
    }

    public Optional<MappingClaim> claim(
            String engine,
            String otherEngine,
            String personalEngine,
            String grammarEngine,
            String grammarV32Engine,
            Duration lease,
            Duration deadline) {
        return claim(
                engine,
                otherEngine,
                personalEngine,
                grammarEngine,
                grammarV32Engine,
                grammarV32Engine,
                lease,
                deadline);
    }

    public Optional<MappingClaim> claim(
            String engine,
            String otherEngine,
            String personalEngine,
            String grammarEngine,
            String grammarV32Engine,
            String grammarV33Engine,
            Duration lease,
            Duration deadline) {
        return claim(
                engine,
                otherEngine,
                personalEngine,
                grammarEngine,
                grammarV32Engine,
                grammarV33Engine,
                grammarV33Engine,
                lease,
                deadline);
    }

    public Optional<MappingClaim> claim(
            String engine,
            String otherEngine,
            String personalEngine,
            String grammarEngine,
            String grammarV32Engine,
            String grammarV33Engine,
            String grammarV34Engine,
            Duration lease,
            Duration deadline) {
        return claim(
                engine,
                otherEngine,
                personalEngine,
                grammarEngine,
                grammarV32Engine,
                grammarV33Engine,
                grammarV34Engine,
                grammarV34Engine,
                lease,
                deadline);
    }

    public Optional<MappingClaim> claim(
            String engine,
            String otherEngine,
            String personalEngine,
            String grammarEngine,
            String grammarV32Engine,
            String grammarV33Engine,
            String grammarV34Engine,
            String grammarV35Engine,
            Duration lease,
            Duration deadline) {
        var ids =
                jdbc.query(
                        """
                SELECT "ID" FROM "MAPPING"."JOBS" WHERE "STATUS"='PENDING'
                AND "ENGINE_VERSION" IN (?,?,?,?,?,?,?,?) AND ("ENGINE_VERSION" NOT IN ('personal-info-mapping/3.0','personal-info-mapping/3.1','personal-info-mapping/3.2','personal-info-mapping/3.3','personal-info-mapping/3.4','personal-info-mapping/3.5') OR EXISTS(SELECT 1 FROM "MAPPING"."CONFIGS" c WHERE c."ID"="MAPPING"."JOBS"."CONFIG_ID" AND c."DEFINITION"->>'engineCompatibility'="MAPPING"."JOBS"."ENGINE_VERSION")) AND "NEXT_ATTEMPT_AT"<=clock_timestamp() AND "ATTEMPTS"<"MAX_ATTEMPTS"
                ORDER BY "PRIORITY","NEXT_ATTEMPT_AT","ID" LIMIT 1 FOR UPDATE SKIP LOCKED
                """,
                        (rs, n) -> rs.getLong(1),
                        engine,
                        otherEngine,
                        personalEngine,
                        grammarEngine,
                        grammarV32Engine,
                        grammarV33Engine,
                        grammarV34Engine,
                        grammarV35Engine);
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.getFirst();
        UUID token = UUID.randomUUID();
        jdbc.update(
                """
                WITH timing AS MATERIALIZED (SELECT clock_timestamp() AS now)
                UPDATE "MAPPING"."JOBS" SET "STATUS"='PROCESSING',"ATTEMPTS"="ATTEMPTS"+1,
                "TOKEN"=?,"LEASE_EXPIRES_AT"=timing.now+?*interval '1 millisecond',
                "HARD_DEADLINE_AT"=timing.now+?*interval '1 millisecond',"ERROR_CODE"=NULL FROM timing WHERE "ID"=?
                """,
                token,
                lease.toMillis(),
                deadline.toMillis(),
                id);
        return Optional.of(readClaim(id));
    }

    public MappingClaim readClaim(long id) {
        return jdbc.queryForObject(
                """
                SELECT j.*,c."REVISION",c."DEFINITION"::text AS definition
                FROM "MAPPING"."JOBS" j JOIN "MAPPING"."CONFIGS" c ON c."ID"=j."CONFIG_ID"
                WHERE j."ID"=?
                """,
                (rs, n) -> claimRow(rs),
                id);
    }

    private MappingClaim claimRow(ResultSet rs) throws SQLException {
        return new MappingClaim(
                rs.getLong("ID"),
                rs.getLong("EXTRACTION_ID"),
                rs.getLong("CONFIG_ID"),
                rs.getString("REVISION"),
                rs.getString("ENGINE_VERSION"),
                rs.getInt("ATTEMPTS"),
                rs.getObject("TOKEN", UUID.class),
                rs.getTimestamp("LEASE_EXPIRES_AT") == null
                        ? null
                        : rs.getTimestamp("LEASE_EXPIRES_AT").toInstant(),
                MappingVersions.config(rs.getString("definition"), json));
    }

    public Job lockJob(long id) {
        return jdbc.queryForObject(
                """
                SELECT "STATUS","TOKEN","LEASE_EXPIRES_AT","ATTEMPTS","HARD_DEADLINE_AT","MAX_ATTEMPTS" FROM "MAPPING"."JOBS" WHERE "ID"=? FOR UPDATE
                """,
                (rs, n) ->
                        new Job(
                                rs.getString(1),
                                rs.getObject(2, UUID.class),
                                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                                rs.getInt(4),
                                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                                rs.getInt(6)),
                id);
    }

    public Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class)
                .toInstant();
    }

    public String digest(long id) {
        return jdbc.queryForObject(
                "SELECT \"DIGEST\" FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                String.class,
                id);
    }

    public Optional<String> result(long id) {
        return jdbc
                .query(
                        "SELECT \"RESULT\"::text FROM \"MAPPING\".\"RESULTS\" WHERE \"JOB_ID\"=?",
                        (rs, n) -> rs.getString(1),
                        id)
                .stream()
                .findFirst();
    }

    public void finish(long id, int schemaVersion, String digest, String quality, String result) {
        jdbc.update(
                """
                INSERT INTO "MAPPING"."RESULTS" ("JOB_ID","SCHEMA_VERSION","QUALITY","DIGEST","RESULT")
                VALUES (?,?,?,?,?::jsonb)
                """,
                id,
                schemaVersion,
                quality,
                digest,
                result);
        jdbc.update(
                """
                UPDATE "MAPPING"."JOBS" SET "STATUS"='COMPLETED',"COMPLETED_AT"=clock_timestamp() WHERE "ID"=?
                """,
                id);
    }

    public List<Long> expired(int limit) {
        return jdbc.query(
                """
                SELECT "ID" FROM "MAPPING"."JOBS"
                WHERE "STATUS"='PROCESSING' AND "ENGINE_VERSION"<>'llm-extraction/1.0' AND "LEASE_EXPIRES_AT"<=clock_timestamp()
                ORDER BY "LEASE_EXPIRES_AT","ID" LIMIT ? FOR UPDATE SKIP LOCKED
                """,
                (rs, n) -> rs.getLong(1),
                limit);
    }

    public boolean pinInput(long id, String digest) {
        return jdbc.update(
                        """
                UPDATE "MAPPING"."JOBS" SET "INPUT_SHA256"=COALESCE("INPUT_SHA256",?)
                WHERE "ID"=? AND ("INPUT_SHA256" IS NULL OR "INPUT_SHA256"=?)
                """,
                        digest,
                        id,
                        digest)
                == 1;
    }

    public void fail(
            long id,
            int attempts,
            int maxAttempts,
            String code,
            boolean retryable,
            Duration backoff) {
        boolean terminal = !retryable || attempts >= maxAttempts;
        jdbc.update(
                """
                UPDATE "MAPPING"."JOBS" SET "STATUS"=?,"TOKEN"=NULL,"LEASE_EXPIRES_AT"=NULL,
                "NEXT_ATTEMPT_AT"=clock_timestamp()+?*interval '1 millisecond',"ERROR_CODE"=?,
                "COMPLETED_AT"=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE "ID"=?
                """,
                terminal ? "FAILED" : "PENDING",
                backoff.toMillis(),
                code,
                terminal,
                id);
    }

    public record Job(
            String status,
            UUID token,
            Instant lease,
            int attempts,
            Instant deadline,
            int maxAttempts) {}
}
