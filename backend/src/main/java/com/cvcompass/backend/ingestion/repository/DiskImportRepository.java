package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.dto.BatchItem;
import com.cvcompass.backend.ingestion.dto.DiskImportEntry;
import com.cvcompass.backend.ingestion.dto.SourceFile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable inventory/history; callers establish transaction boundaries where needed. */
@Repository
public class DiskImportRepository {
    private final JdbcTemplate jdbc;

    public DiskImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void observe(String key, SourceFile file, UUID scan, Instant now) {
        jdbc.update(
                """
                INSERT INTO "INGESTION"."DISK_OBSERVATIONS" AS old
                ("SOURCE_KEY","FILE_NAME","SIZE_BYTES","MODIFIED","FILE_KEY","OBSERVED_SINCE","LAST_SCAN","NEXT_READ_AT")
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT ("SOURCE_KEY","FILE_NAME") DO UPDATE SET
                "OBSERVED_SINCE"=CASE WHEN old."SIZE_BYTES"=excluded."SIZE_BYTES" AND old."MODIFIED"=excluded."MODIFIED"
                  AND old."FILE_KEY"=excluded."FILE_KEY" THEN old."OBSERVED_SINCE" ELSE excluded."OBSERVED_SINCE" END,
                "READ_ATTEMPTS"=CASE WHEN old."SIZE_BYTES"=excluded."SIZE_BYTES" AND old."MODIFIED"=excluded."MODIFIED"
                  AND old."FILE_KEY"=excluded."FILE_KEY" THEN old."READ_ATTEMPTS" ELSE 0 END,
                "ERROR_CODE"=CASE WHEN old."SIZE_BYTES"=excluded."SIZE_BYTES" AND old."MODIFIED"=excluded."MODIFIED"
                  AND old."FILE_KEY"=excluded."FILE_KEY" THEN old."ERROR_CODE" ELSE NULL END,
                "NEXT_READ_AT"=CASE WHEN old."SIZE_BYTES"=excluded."SIZE_BYTES" AND old."MODIFIED"=excluded."MODIFIED"
                  AND old."FILE_KEY"=excluded."FILE_KEY" THEN old."NEXT_READ_AT" ELSE excluded."NEXT_READ_AT" END,
                "SIZE_BYTES"=excluded."SIZE_BYTES", "MODIFIED"=excluded."MODIFIED", "FILE_KEY"=excluded."FILE_KEY", "LAST_SCAN"=excluded."LAST_SCAN"
                """,
                key,
                file.name(),
                file.size(),
                file.modified(),
                file.fileKey(),
                Timestamp.from(now),
                scan,
                Timestamp.from(now));
    }

    public List<SourceFile> stable(
            String key,
            UUID scan,
            Instant cutoff,
            String after,
            int limit,
            int maxAttempts,
            Instant now) {
        return jdbc.query(
                """
                SELECT * FROM "INGESTION"."DISK_OBSERVATIONS" WHERE "SOURCE_KEY"=? AND "LAST_SCAN"=? AND "OBSERVED_SINCE"<=?
                  AND "FILE_NAME" COLLATE "C">? COLLATE "C" AND COALESCE("ERROR_CODE",'')<>'SOURCE_READ_NEEDS_ATTENTION' AND "READ_ATTEMPTS"<? AND "NEXT_READ_AT"<=? ORDER BY "FILE_NAME" COLLATE "C" LIMIT ?
                """,
                (rs, row) -> snapshot(rs),
                key,
                scan,
                Timestamp.from(cutoff),
                after,
                maxAttempts,
                Timestamp.from(now),
                limit);
    }

    public void readFailed(String key, String name, Instant next, int maximum) {
        int spent =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                """
                SELECT COALESCE(MAX(h."ATTEMPTS"-h."READ_FAILURES_APPLIED"),0)
                FROM "INGESTION"."DISK_IMPORT_FILES" h JOIN "INGESTION"."DISK_OBSERVATIONS" o
                ON h."SOURCE_KEY"=o."SOURCE_KEY" AND h."FILE_NAME"=o."FILE_NAME"
                AND h."SIZE_BYTES"=o."SIZE_BYTES" AND h."MODIFIED"=o."MODIFIED" AND h."FILE_KEY"=o."FILE_KEY"
                WHERE h."SOURCE_KEY"=? AND h."FILE_NAME"=? AND h."STATE" IN ('PENDING','RETRY_WAIT')
                """,
                                Integer.class,
                                key,
                                name));
        jdbc.update(
                """
                UPDATE "INGESTION"."DISK_OBSERVATIONS" SET "READ_ATTEMPTS"="READ_ATTEMPTS"+1,"NEXT_READ_AT"=?,
                "ERROR_CODE"=CASE WHEN "READ_ATTEMPTS"+1+?>=? THEN 'SOURCE_READ_NEEDS_ATTENTION' ELSE 'SOURCE_READ_RETRY' END
                WHERE "SOURCE_KEY"=? AND "FILE_NAME"=?
                """,
                Timestamp.from(next),
                spent,
                maximum,
                key,
                name);
        jdbc.update(
                """
                UPDATE "INGESTION"."DISK_IMPORT_FILES" h SET
                "ATTEMPTS"=h."ATTEMPTS"+GREATEST(0,o."READ_ATTEMPTS"-h."READ_FAILURES_APPLIED"),
                "READ_FAILURES_APPLIED"=o."READ_ATTEMPTS",
                "STATE"=CASE WHEN o."ERROR_CODE"='SOURCE_READ_NEEDS_ATTENTION' THEN 'NEEDS_ATTENTION' ELSE h."STATE" END,
                "ERROR_CODE"=o."ERROR_CODE"
                FROM "INGESTION"."DISK_OBSERVATIONS" o WHERE h."SOURCE_KEY"=o."SOURCE_KEY" AND h."FILE_NAME"=o."FILE_NAME"
                AND h."SIZE_BYTES"=o."SIZE_BYTES" AND h."MODIFIED"=o."MODIFIED" AND h."FILE_KEY"=o."FILE_KEY"
                AND h."SOURCE_KEY"=? AND h."FILE_NAME"=? AND h."STATE" IN ('PENDING','RETRY_WAIT')

                """,
                key,
                name);
    }

    public DiskImportEntry identify(String key, SourceFile file, String sha256, Instant now) {
        int reads =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT \"READ_ATTEMPTS\" FROM \"INGESTION\".\"DISK_OBSERVATIONS\" WHERE \"SOURCE_KEY\"=? AND \"FILE_NAME\"=?",
                                Integer.class,
                                key,
                                file.name()));
        jdbc.update(
                """
                INSERT INTO "INGESTION"."DISK_IMPORT_FILES" AS old
                ("SOURCE_KEY","FILE_NAME","SHA256","SIZE_BYTES","MODIFIED","FILE_KEY","STATE","NEXT_ATTEMPT_AT","NEXT_MOVE_AT","TARGET_NAME","ATTEMPTS","READ_FAILURES_APPLIED")
                VALUES (?,?,?,?,?,?,'PENDING',?,?,?,?,?) ON CONFLICT ("SOURCE_KEY","FILE_NAME","SHA256") DO UPDATE SET
                "ATTEMPTS"=old."ATTEMPTS"+GREATEST(0,excluded."READ_FAILURES_APPLIED"-old."READ_FAILURES_APPLIED"),
                "READ_FAILURES_APPLIED"=excluded."READ_FAILURES_APPLIED"
                WHERE old."STATE" IN ('PENDING','RETRY_WAIT')
                """,
                key,
                file.name(),
                sha256,
                file.size(),
                file.modified(),
                file.fileKey(),
                Timestamp.from(now),
                Timestamp.from(now),
                UUID.randomUUID().toString().replace("-", "") + ".pdf",
                reads,
                reads);
        return jdbc.queryForObject(
                "SELECT * FROM \"INGESTION\".\"DISK_IMPORT_FILES\" WHERE \"SOURCE_KEY\"=? AND \"FILE_NAME\"=? AND \"SHA256\"=?",
                (rs, row) -> entry(rs),
                key,
                file.name(),
                sha256);
    }

    public boolean due(long id, Instant now, int maximum) {
        jdbc.update(
                """
                UPDATE "INGESTION"."DISK_IMPORT_FILES" SET "STATE"='FAILED_MOVE',"ERROR_CODE"='SOURCE_READ_RETRIES_EXHAUSTED'
                WHERE "ID"=? AND "STATE" IN ('PENDING','RETRY_WAIT') AND "ATTEMPTS">=?
                """,
                id,
                maximum);
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                SELECT "STATE" IN ('PENDING','RETRY_WAIT') AND "NEXT_ATTEMPT_AT"<=? FROM "INGESTION"."DISK_IMPORT_FILES" WHERE "ID"=?
                """,
                        Boolean.class,
                        Timestamp.from(now),
                        id));
    }

    public void queue(List<DiskImportEntry> entries) {
        for (var entry : entries) {
            int changed =
                    jdbc.update(
                            "UPDATE \"INGESTION\".\"DISK_IMPORT_FILES\" SET \"STATE\"='QUEUED',\"ATTEMPTS\"=\"ATTEMPTS\"+1 WHERE \"ID\"=? AND \"STATE\" IN ('PENDING','RETRY_WAIT')",
                            entry.id());
            if (changed != 1) throw new IllegalStateException("Import queue ownership mismatch");
        }
    }

    public void attached(List<DiskImportEntry> entries, long batchId) {
        for (var entry : entries) {
            int changed =
                    jdbc.update(
                            "UPDATE \"INGESTION\".\"DISK_IMPORT_FILES\" SET \"BATCH_ID\"=? WHERE \"ID\"=? AND \"STATE\"='QUEUED'",
                            batchId,
                            entry.id());
            if (changed != 1) throw new IllegalStateException("Import batch ownership mismatch");
        }
    }

    public void started(long id, SourceFile file, long batchId) {
        int changed =
                jdbc.update(
                        """
                UPDATE "INGESTION"."DISK_IMPORT_FILES" SET "STATE"='IMPORTING', "BATCH_ID"=?,
                "SIZE_BYTES"=?,"MODIFIED"=?,"FILE_KEY"=?,"UPDATED_AT"=CURRENT_TIMESTAMP WHERE "ID"=? AND "STATE"='QUEUED'
                """,
                        batchId,
                        file.size(),
                        file.modified(),
                        file.fileKey(),
                        id);
        if (changed != 1) throw new IllegalStateException("Import history ownership mismatch");
    }

    public void outcome(long id, BatchItem item, int maxAttempts, Instant next) {
        String code = item.errorCode();
        boolean changed = "SOURCE_CHANGED".equals(code);
        String state;
        if (item.documentId() != null) state = "SUCCEEDED_MOVE";
        else if (changed) state = "RETRY_WAIT";
        else if (!"FILE_STORAGE_ERROR".equals(code) || attempts(id) >= maxAttempts)
            state = "FAILED_MOVE";
        else state = "RETRY_WAIT";
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("Import outcome requires item transaction");
        int updated =
                jdbc.update(
                        """
                UPDATE "INGESTION"."DISK_IMPORT_FILES" SET "STATE"=?,"DOCUMENT_ID"=?,"ERROR_CODE"=?,"NEXT_ATTEMPT_AT"=?,
                "ATTEMPTS"="ATTEMPTS"-?,"UPDATED_AT"=CURRENT_TIMESTAMP WHERE "ID"=? AND "STATE"='IMPORTING'
                """,
                        state,
                        item.documentId(),
                        code,
                        Timestamp.from(next),
                        changed ? 1 : 0,
                        id);
        if (updated != 1) throw new IllegalStateException("Import outcome ownership mismatch");
    }

    public int attempts(long id) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT \"ATTEMPTS\" FROM \"INGESTION\".\"DISK_IMPORT_FILES\" WHERE \"ID\"=?",
                        Integer.class,
                        id));
    }

    public void interrupted(String key, Instant next, int maximum) {
        // QUEUED cannot have streamed: started commits IMPORTING before opening input.
        jdbc.update(
                """
                UPDATE "INGESTION"."DISK_IMPORT_FILES" SET
                "STATE"=CASE WHEN "ATTEMPTS">=? THEN 'NEEDS_ATTENTION' ELSE 'RETRY_WAIT' END,
                "ERROR_CODE"='BATCH_PREPARATION_INTERRUPTED',"NEXT_ATTEMPT_AT"=?,"UPDATED_AT"=CURRENT_TIMESTAMP
                WHERE "SOURCE_KEY"=? AND "STATE"='QUEUED'
                """,
                maximum,
                Timestamp.from(next),
                key);
        jdbc.update(
                """
                UPDATE "INGESTION"."DISK_IMPORT_FILES" SET "STATE"='NEEDS_ATTENTION',"ERROR_CODE"='INTERRUPTED',"UPDATED_AT"=CURRENT_TIMESTAMP
                WHERE "SOURCE_KEY"=? AND "STATE"='IMPORTING'
                """,
                key);
    }

    public List<DiskImportEntry> moves(String key, Instant now, long after, int limit) {
        return jdbc.query(
                """
                SELECT * FROM "INGESTION"."DISK_IMPORT_FILES" WHERE "SOURCE_KEY"=? AND "STATE" IN ('SUCCEEDED_MOVE','FAILED_MOVE')
                AND "NEXT_MOVE_AT"<=? AND "ID">? ORDER BY "ID" LIMIT ?
                """,
                (rs, row) -> entry(rs),
                key,
                Timestamp.from(now),
                after,
                limit);
    }

    public String storageKey(long document) {
        return jdbc.queryForObject(
                "SELECT \"STORAGE_PATH\" FROM \"INGESTION\".\"DOCUMENTS\" WHERE \"ID\"=?",
                String.class,
                document);
    }

    public void attention(long id, String code) {
        jdbc.update(
                "UPDATE \"INGESTION\".\"DISK_IMPORT_FILES\" SET \"STATE\"='NEEDS_ATTENTION',\"MOVE_ERROR_CODE\"=?,\"UPDATED_AT\"=CURRENT_TIMESTAMP WHERE \"ID\"=?",
                code,
                id);
    }

    public void moveStarted(long id) {
        jdbc.update(
                "UPDATE \"INGESTION\".\"DISK_IMPORT_FILES\" SET \"MOVE_ATTEMPTS\"=\"MOVE_ATTEMPTS\"+1 WHERE \"ID\"=?",
                id);
    }

    public void moved(DiskImportEntry item) {
        jdbc.update(
                "UPDATE \"INGESTION\".\"DISK_IMPORT_FILES\" SET \"STATE\"=?,\"MOVE_ERROR_CODE\"=NULL,\"UPDATED_AT\"=CURRENT_TIMESTAMP WHERE \"ID\"=?",
                item.documentId() == null ? "FAILED" : "PROCESSED",
                item.id());
    }

    public void moveFailed(long id, Instant next) {
        jdbc.update(
                "UPDATE \"INGESTION\".\"DISK_IMPORT_FILES\" SET \"NEXT_MOVE_AT\"=?,\"MOVE_ERROR_CODE\"='MOVE_FAILED',\"UPDATED_AT\"=CURRENT_TIMESTAMP WHERE \"ID\"=?",
                Timestamp.from(next),
                id);
    }

    private static SourceFile snapshot(ResultSet rs) throws SQLException {
        return new SourceFile(
                rs.getString("FILE_NAME"),
                rs.getLong("SIZE_BYTES"),
                rs.getString("MODIFIED"),
                rs.getString("FILE_KEY"));
    }

    private static DiskImportEntry entry(ResultSet rs) throws SQLException {
        return new DiskImportEntry(
                rs.getLong("ID"),
                snapshot(rs),
                rs.getString("SHA256"),
                rs.getString("STATE"),
                rs.getInt("ATTEMPTS"),
                rs.getInt("MOVE_ATTEMPTS"),
                rs.getObject("DOCUMENT_ID", Long.class),
                rs.getString("TARGET_NAME"));
    }
}
