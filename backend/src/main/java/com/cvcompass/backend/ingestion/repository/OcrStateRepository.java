package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.dto.OcrAttemptState;
import com.cvcompass.backend.ingestion.dto.OcrClaimRequest;
import com.cvcompass.backend.ingestion.dto.OcrJobState;
import com.cvcompass.backend.ingestion.dto.OcrResultPayload;
import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.enums.OcrAttemptStatus;
import com.cvcompass.backend.ingestion.enums.OcrEndReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL row locks and writes participate in the caller's Spring transaction. */
@Repository
public class OcrStateRepository {
    private final JdbcTemplate jdbc;

    public OcrStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant wallClock() {
        return java.util.Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    public void lockClaimKey(String worker, UUID key) {
        // Serialize one idempotency key, not the queue. Hash collisions only serialize extra calls.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                worker + ":" + key);
    }

    public Optional<OcrJobState> lockJob(long id) {
        return jdbc
                .query(
                        "SELECT * FROM \"INGESTION\".\"PROCESSING_JOBS\" WHERE \"ID\"=? FOR UPDATE",
                        (rs, n) -> job(rs),
                        id)
                .stream()
                .findFirst();
    }

    public Optional<OcrJobState> lockReady(String profile) {
        return jdbc
                .query(
                        """
                SELECT * FROM "INGESTION"."PROCESSING_JOBS" WHERE "PROFILE_REVISION"=? AND "STATUS"='PENDING'
                AND "NEXT_ATTEMPT_AT"<=clock_timestamp() AND "ATTEMPTS_STARTED"<"MAX_ATTEMPTS"
                ORDER BY "NEXT_ATTEMPT_AT","ID" LIMIT 1 FOR UPDATE SKIP LOCKED
                """,
                        (rs, n) -> job(rs),
                        profile)
                .stream()
                .findFirst();
    }

    public Optional<OcrAttemptState> findClaim(String worker, UUID key) {
        return jdbc
                .query(
                        "SELECT * FROM \"INGESTION\".\"OCR_ATTEMPTS\" WHERE \"WORKER_ID\"=? AND \"CLAIM_REQUEST_ID\"=?",
                        (rs, n) -> attempt(rs),
                        worker,
                        key)
                .stream()
                .findFirst();
    }

    public Optional<OcrAttemptState> findAttempt(UUID publicId) {
        return jdbc
                .query(
                        "SELECT * FROM \"INGESTION\".\"OCR_ATTEMPTS\" WHERE \"PUBLIC_ID\"=?",
                        (rs, n) -> attempt(rs),
                        publicId)
                .stream()
                .findFirst();
    }

    public OcrAttemptState lockAttempt(long id) {
        return jdbc.queryForObject(
                "SELECT * FROM \"INGESTION\".\"OCR_ATTEMPTS\" WHERE \"ID\"=? FOR UPDATE",
                (rs, n) -> attempt(rs),
                id);
    }

    public OcrAttemptState insertAttempt(
            OcrJobState job,
            OcrClaimRequest request,
            String claimDigest,
            Instant now,
            Instant lease,
            Instant deadline,
            Instant reclaim) {
        long id =
                java.util.Objects.requireNonNull(
                        jdbc.queryForObject(
                                """
                INSERT INTO "INGESTION"."OCR_ATTEMPTS" ("PUBLIC_ID","JOB_ID","ATTEMPT_NO","WORKER_ID",
                "CLAIM_REQUEST_ID","CLAIM_BODY_SHA256","LEASE_TOKEN_HASH","LEASE_EXPIRES_AT",
                "HARD_DEADLINE_AT","RECLAIM_AFTER","STATUS","STARTED_AT")
                VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',?) RETURNING "ID"
                """,
                                Long.class,
                                UUID.randomUUID(),
                                job.id(),
                                job.attemptsStarted() + 1,
                                request.workerId(),
                                request.claimRequestId(),
                                claimDigest,
                                request.leaseTokenHash(),
                                ts(lease),
                                ts(deadline),
                                ts(reclaim),
                                ts(now)));
        return lockAttempt(id);
    }

    public void claimed(OcrJobState job, OcrAttemptState attempt, Instant now) {
        changed(
                jdbc.update(
                        """
                UPDATE "INGESTION"."PROCESSING_JOBS" SET "STATUS"='PROCESSING',"CURRENT_ATTEMPT_ID"=?,
                "ATTEMPTS_STARTED"=?,"RETRY_COUNT"=?,"STARTED_AT"=COALESCE("STARTED_AT",?),
                "COMPLETED_AT"=NULL,"END_REASON"=NULL,"ERROR_CODE"=NULL WHERE "ID"=?
                """,
                        attempt.id(),
                        attempt.attemptNo(),
                        attempt.attemptNo() - 1,
                        ts(now),
                        job.id()));
        document(job.documentId(), JobStatus.PROCESSING);
    }

    public void renewed(long attemptId, Instant expiry) {
        changed(
                jdbc.update(
                        "UPDATE \"INGESTION\".\"OCR_ATTEMPTS\" SET \"LEASE_EXPIRES_AT\"=? WHERE \"ID\"=? AND \"STATUS\"='ACTIVE'",
                        ts(expiry),
                        attemptId));
    }

    public void finishAttempt(
            long id,
            OcrAttemptStatus status,
            OcrEndReason reason,
            String code,
            String digest,
            Instant now) {
        changed(
                jdbc.update(
                        """
                UPDATE "INGESTION"."OCR_ATTEMPTS" SET "STATUS"=?,"END_REASON"=?,"ERROR_CODE"=?,
                "OUTCOME_SHA256"=?,"FINISHED_AT"=? WHERE "ID"=? AND "STATUS"='ACTIVE'
                """,
                        status.name(),
                        reason.name(),
                        code,
                        digest,
                        ts(now),
                        id));
    }

    public void finishJob(
            OcrJobState job, JobStatus status, OcrEndReason reason, String code, Instant now) {
        changed(
                jdbc.update(
                        """
                UPDATE "INGESTION"."PROCESSING_JOBS" SET "STATUS"=?,"END_REASON"=?,"ERROR_CODE"=?,"COMPLETED_AT"=? WHERE "ID"=?
                """,
                        status.name(),
                        reason.name(),
                        code,
                        ts(now),
                        job.id()));
        document(job.documentId(), status);
    }

    public void retry(OcrJobState job, String code, Instant ready) {
        changed(
                jdbc.update(
                        """
                UPDATE "INGESTION"."PROCESSING_JOBS" SET "STATUS"='PENDING',"ERROR_CODE"=?,"END_REASON"=NULL,
                "NEXT_ATTEMPT_AT"=?,"COMPLETED_AT"=NULL WHERE "ID"=?
                """,
                        code,
                        ts(ready),
                        job.id()));
        document(job.documentId(), JobStatus.PENDING);
    }

    public long insertResult(OcrJobState job, OcrAttemptState attempt, OcrResultPayload result) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        """
                INSERT INTO "INGESTION"."EXTRACTIONS" ("CV_DOCUMENT_ID","PROCESSING_JOB_ID","ATTEMPT_ID",
                "EXTRACTION_METHOD","OCR_RESULT","OCR_STATUS","PROFILE_REVISION","PROVENANCE")
                VALUES (?,?,?,?,?::jsonb,?,?,?::jsonb) RETURNING "ID"
                """,
                        Long.class,
                        job.documentId(),
                        job.id(),
                        attempt.id(),
                        result.method().name(),
                        result.resultJson(),
                        result.status(),
                        result.profileRevision(),
                        result.provenanceJson()));
    }

    public Long extractionId(long attemptId) {
        return jdbc
                .query(
                        "SELECT \"ID\" FROM \"INGESTION\".\"EXTRACTIONS\" WHERE \"ATTEMPT_ID\"=?",
                        (rs, n) -> rs.getLong(1),
                        attemptId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<Long> lockExpiredJobs(int limit) {
        return jdbc.query(
                """
                SELECT j."ID" FROM "INGESTION"."PROCESSING_JOBS" j JOIN "INGESTION"."OCR_ATTEMPTS" a
                ON j."CURRENT_ATTEMPT_ID"=a."ID" AND j."ID"=a."JOB_ID"
                WHERE j."PROFILE_REVISION" IS NOT NULL AND j."STATUS"='PROCESSING' AND a."STATUS"='ACTIVE'
                AND a."LEASE_EXPIRES_AT"<=clock_timestamp()
                ORDER BY j."ID" LIMIT ? FOR UPDATE OF j SKIP LOCKED
                """,
                (rs, n) -> rs.getLong(1),
                limit);
    }

    private void document(long id, JobStatus status) {
        changed(
                jdbc.update(
                        "UPDATE \"INGESTION\".\"DOCUMENTS\" SET \"STATUS\"=? WHERE \"ID\"=?",
                        status.name(),
                        id));
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("OCR ownership transition mismatch");
    }

    private static Timestamp ts(Instant time) {
        return Timestamp.from(time);
    }

    private static OcrJobState job(ResultSet rs) throws SQLException {
        return new OcrJobState(
                rs.getLong("ID"),
                rs.getLong("CV_DOCUMENT_ID"),
                rs.getString("PROFILE_REVISION"),
                rs.getInt("GENERATION"),
                rs.getInt("MAX_ATTEMPTS"),
                rs.getInt("ATTEMPTS_STARTED"),
                instant(rs, "NEXT_ATTEMPT_AT"),
                (Long) rs.getObject("CURRENT_ATTEMPT_ID"),
                JobStatus.valueOf(rs.getString("STATUS")));
    }

    private static OcrAttemptState attempt(ResultSet rs) throws SQLException {
        return new OcrAttemptState(
                rs.getLong("ID"),
                rs.getObject("PUBLIC_ID", UUID.class),
                rs.getLong("JOB_ID"),
                rs.getInt("ATTEMPT_NO"),
                rs.getString("WORKER_ID"),
                rs.getObject("CLAIM_REQUEST_ID", UUID.class),
                rs.getString("CLAIM_BODY_SHA256"),
                rs.getString("LEASE_TOKEN_HASH"),
                instant(rs, "LEASE_EXPIRES_AT"),
                instant(rs, "HARD_DEADLINE_AT"),
                instant(rs, "RECLAIM_AFTER"),
                OcrAttemptStatus.valueOf(rs.getString("STATUS")),
                rs.getString("OUTCOME_SHA256"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
