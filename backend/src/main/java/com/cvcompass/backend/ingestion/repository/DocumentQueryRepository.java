package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.dto.DocumentDetailRow;
import com.cvcompass.backend.ingestion.dto.DocumentMetadata;
import com.cvcompass.backend.ingestion.dto.DocumentOverview;
import com.cvcompass.backend.ingestion.dto.DocumentProcessing;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded read model; list queries never select OCR JSON/text or credentials. */
@Repository
public class DocumentQueryRepository {
    private static final String COLUMNS =
            """
            d."ID", d."ORIGINAL_FILENAME", d."FILE_SIZE", d."MIME_TYPE", d."SHA256",
            d."STATUS", d."CREATED_AT", d."BATCH_ID", j."ID" AS job_id, j."STATUS" AS job_status,
            j."ATTEMPTS_STARTED", j."MAX_ATTEMPTS", j."NEXT_ATTEMPT_AT", j."STARTED_AT",
            j."COMPLETED_AT", j."END_REASON", j."ERROR_CODE", x."ID" AS extraction_id,
            x."OCR_RESULT" IS NOT NULL AS has_pages
            """;
    // Latest job by immutable ID across profiles, and only that job's result. Never fall back
    // to an older successful generation when the newest job is pending/failed.
    private static final String JOINS =
            """
            FROM "INGESTION"."DOCUMENTS" d
            LEFT JOIN LATERAL (SELECT * FROM "INGESTION"."PROCESSING_JOBS"
                WHERE "CV_DOCUMENT_ID" = d."ID" ORDER BY "ID" DESC LIMIT 1) j ON true
            LEFT JOIN LATERAL (SELECT "ID", "OCR_RESULT", "RAW_TEXT" FROM "INGESTION"."EXTRACTIONS"
                WHERE "PROCESSING_JOB_ID" = j."ID" AND "CV_DOCUMENT_ID" = d."ID"
                ORDER BY "ID" DESC LIMIT 1) x ON true
            """;
    private final JdbcTemplate jdbc;

    public DocumentQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DocumentOverview> list(Long beforeId, int limit) {
        String cursor = beforeId == null ? "" : " WHERE d.\"ID\" < ?";
        String sql = "SELECT " + COLUMNS + JOINS + cursor + " ORDER BY d.\"ID\" DESC LIMIT ?";
        return beforeId == null
                ? jdbc.query(sql, (rs, row) -> overview(rs), limit)
                : jdbc.query(sql, (rs, row) -> overview(rs), beforeId, limit);
    }

    public Optional<DocumentDetailRow> find(long id) {
        return jdbc
                .query(
                        "SELECT "
                                + COLUMNS
                                + ", x.\"OCR_RESULT\"::text AS result_json, x.\"RAW_TEXT\" "
                                + JOINS
                                + " WHERE d.\"ID\" = ?",
                        (rs, row) ->
                                new DocumentDetailRow(
                                        overview(rs),
                                        (Long) rs.getObject("extraction_id"),
                                        rs.getString("result_json"),
                                        rs.getString("RAW_TEXT")),
                        id)
                .stream()
                .findFirst();
    }

    private static DocumentOverview overview(ResultSet rs) throws SQLException {
        var metadata =
                new DocumentMetadata(
                        rs.getLong("ID"),
                        rs.getString("ORIGINAL_FILENAME"),
                        rs.getLong("FILE_SIZE"),
                        rs.getString("MIME_TYPE"),
                        rs.getString("SHA256"),
                        DocumentStatus.valueOf(rs.getString("STATUS")),
                        instant(rs, "CREATED_AT"));
        Long jobId = (Long) rs.getObject("job_id");
        String availability =
                rs.getObject("extraction_id") == null
                        ? "NONE"
                        : rs.getBoolean("has_pages") ? "PAGE_RESULT" : "LEGACY";
        var processing =
                new DocumentProcessing(
                        jobId,
                        jobId == null ? "NOT_SCHEDULED" : rs.getString("job_status"),
                        (Integer) rs.getObject("ATTEMPTS_STARTED"),
                        (Integer) rs.getObject("MAX_ATTEMPTS"),
                        instant(rs, "NEXT_ATTEMPT_AT"),
                        instant(rs, "STARTED_AT"),
                        instant(rs, "COMPLETED_AT"),
                        rs.getString("END_REASON"),
                        rs.getString("ERROR_CODE"),
                        availability);
        return new DocumentOverview(
                metadata, (Long) rs.getObject("BATCH_ID"), "STORED", processing);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
