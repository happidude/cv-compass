package com.cvcompass.backend.mapping.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The service always locks the job before its attempt, including replay and reaping. */
@Repository
public class MappingRemoteRepository {
    private final JdbcTemplate jdbc;

    public MappingRemoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockRequest(String worker, UUID request) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                "mapping-remote:" + worker + ":" + request);
    }

    public Optional<UUID> replay(String worker, UUID request) {
        return jdbc
                .query(
                        """
                SELECT "PUBLIC_ID" FROM "MAPPING"."ATTEMPTS" WHERE "WORKER_ID"=? AND "CLAIM_REQUEST_ID"=?
                """,
                        (rs, n) -> rs.getObject(1, UUID.class),
                        worker,
                        request)
                .stream()
                .findFirst();
    }

    public Optional<Long> ready() {
        return jdbc
                .query(
                        """
                SELECT j."ID" FROM "MAPPING"."JOBS" j JOIN "MAPPING"."CONFIGS" c ON c."ID"=j."CONFIG_ID"
                WHERE j."STATUS"='PENDING' AND j."ENGINE_VERSION"='llm-extraction/1.0'
                AND c."DEFINITION"->>'engineCompatibility'=j."ENGINE_VERSION"
                AND j."NEXT_ATTEMPT_AT"<=clock_timestamp() AND j."ATTEMPTS"<j."MAX_ATTEMPTS"
                ORDER BY j."NEXT_ATTEMPT_AT",j."ID" LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                """,
                        (rs, n) -> rs.getLong(1))
                .stream()
                .findFirst();
    }

    public void consumeInputFailure(long job) {
        jdbc.update(
                """
                UPDATE "MAPPING"."JOBS" SET "ATTEMPTS"="ATTEMPTS"+1 WHERE "ID"=?
                """,
                job);
    }

    public void start(long job, UUID token, Duration lease, Duration deadline) {
        jdbc.update(
                """
                WITH timing AS MATERIALIZED (SELECT clock_timestamp() AS now)
                UPDATE "MAPPING"."JOBS" SET "STATUS"='PROCESSING',"ATTEMPTS"="ATTEMPTS"+1,
                "TOKEN"=?,"LEASE_EXPIRES_AT"=timing.now+?*interval '1 millisecond',
                "HARD_DEADLINE_AT"=timing.now+?*interval '1 millisecond',"ERROR_CODE"=NULL
                FROM timing WHERE "ID"=?
                """,
                token,
                lease.toMillis(),
                deadline.toMillis(),
                job);
    }

    public void insert(
            long job,
            UUID publicId,
            String worker,
            UUID request,
            String bodyHash,
            String tokenHash,
            String provenance,
            Duration lease,
            Duration grace) {
        jdbc.update(
                """
                INSERT INTO "MAPPING"."ATTEMPTS" ("PUBLIC_ID","JOB_ID","ATTEMPT_NO","WORKER_ID",
                "CLAIM_REQUEST_ID","CLAIM_BODY_SHA256","LEASE_TOKEN_HASH","LEASE_EXPIRES_AT",
                "HARD_DEADLINE_AT","RECLAIM_AFTER","STATUS","STARTED_AT","PROVENANCE")
                SELECT ?,"ID","ATTEMPTS",?,?,?, ?,"LEASE_EXPIRES_AT","HARD_DEADLINE_AT",
                "HARD_DEADLINE_AT"+?*interval '1 millisecond','ACTIVE',
                "LEASE_EXPIRES_AT"-?*interval '1 millisecond',?::jsonb FROM "MAPPING"."JOBS" WHERE "ID"=?
                """,
                publicId,
                worker,
                request,
                bodyHash,
                tokenHash,
                grace.toMillis(),
                lease.toMillis(),
                provenance,
                job);
    }

    public Optional<Long> jobId(UUID id) {
        return jdbc
                .query(
                        "SELECT \"JOB_ID\" FROM \"MAPPING\".\"ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                        (rs, n) -> rs.getLong(1),
                        id)
                .stream()
                .findFirst();
    }

    public Attempt lock(UUID id) {
        return jdbc.queryForObject(
                """
                SELECT a.*,j."INPUT_SHA256" FROM "MAPPING"."ATTEMPTS" a
                JOIN "MAPPING"."JOBS" j ON j."ID"=a."JOB_ID" WHERE a."PUBLIC_ID"=? FOR UPDATE OF a
                """,
                (rs, n) -> row(rs),
                id);
    }

    private static Attempt row(ResultSet rs) throws SQLException {
        return new Attempt(
                rs.getObject("PUBLIC_ID", UUID.class),
                rs.getLong("JOB_ID"),
                rs.getInt("ATTEMPT_NO"),
                rs.getString("WORKER_ID"),
                rs.getString("CLAIM_BODY_SHA256"),
                rs.getString("LEASE_TOKEN_HASH"),
                rs.getTimestamp("LEASE_EXPIRES_AT").toInstant(),
                rs.getTimestamp("HARD_DEADLINE_AT").toInstant(),
                rs.getTimestamp("RECLAIM_AFTER").toInstant(),
                rs.getString("STATUS"),
                rs.getString("OUTCOME_SHA256"),
                rs.getString("PROVENANCE"),
                rs.getString("INPUT_SHA256"),
                rs.getString("ERROR_CODE"));
    }

    public void heartbeat(Attempt attempt, Duration lease) {
        jdbc.update(
                """
                UPDATE "MAPPING"."ATTEMPTS" SET "LEASE_EXPIRES_AT"=LEAST(clock_timestamp()+?*interval '1 millisecond',"HARD_DEADLINE_AT") WHERE "PUBLIC_ID"=?
                """,
                lease.toMillis(),
                attempt.id());
        jdbc.update(
                """
                UPDATE "MAPPING"."JOBS" j SET "LEASE_EXPIRES_AT"=a."LEASE_EXPIRES_AT"
                FROM "MAPPING"."ATTEMPTS" a WHERE a."PUBLIC_ID"=? AND j."ID"=a."JOB_ID"
                """,
                attempt.id());
    }

    public void end(Attempt attempt, String status, String code, String outcome) {
        jdbc.update(
                """
                UPDATE "MAPPING"."ATTEMPTS" SET "STATUS"=?,"FINISHED_AT"=clock_timestamp(),
                "END_REASON"=?,"ERROR_CODE"=?,"OUTCOME_SHA256"=? WHERE "PUBLIC_ID"=?
                """,
                status,
                code == null ? "RESULT_ACCEPTED" : code,
                code,
                outcome,
                attempt.id());
    }

    public void quarantine(Attempt attempt) {
        jdbc.update(
                """
                UPDATE "MAPPING"."JOBS" SET "NEXT_ATTEMPT_AT"=GREATEST("NEXT_ATTEMPT_AT",?::timestamptz) WHERE "ID"=?
                """,
                java.sql.Timestamp.from(attempt.reclaim()),
                attempt.jobId());
    }

    public List<UUID> expired(int limit) {
        return jdbc.query(
                """
                SELECT a."PUBLIC_ID" FROM "MAPPING"."JOBS" j JOIN "MAPPING"."ATTEMPTS" a ON a."JOB_ID"=j."ID"
                WHERE a."STATUS"='ACTIVE' AND a."LEASE_EXPIRES_AT"<=clock_timestamp()
                ORDER BY a."LEASE_EXPIRES_AT",j."ID" LIMIT ? FOR UPDATE OF j SKIP LOCKED
                """,
                (rs, n) -> rs.getObject(1, UUID.class),
                limit);
    }

    public record Attempt(
            UUID id,
            long jobId,
            int number,
            String worker,
            String claimHash,
            String tokenHash,
            Instant lease,
            Instant deadline,
            Instant reclaim,
            String status,
            String outcomeHash,
            String provenance,
            String inputHash,
            String errorCode) {}
}
