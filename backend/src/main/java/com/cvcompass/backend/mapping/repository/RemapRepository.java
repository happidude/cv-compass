package com.cvcompass.backend.mapping.repository;

import com.cvcompass.backend.mapping.dto.RemapRequest;
import com.cvcompass.backend.mapping.dto.RemapRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RemapRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final ZoneId IMPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String PROGRESS =
            """
            SELECT r.*,
              (SELECT count(*) FROM "MAPPING"."JOBS" j WHERE j."REMAP_RUN_ID"=r."ID" AND j."STATUS"='COMPLETED') AS completed,
              (SELECT count(*) FROM "MAPPING"."JOBS" j WHERE j."REMAP_RUN_ID"=r."ID" AND j."STATUS"='FAILED') AS failed,
              (SELECT count(*) FROM "MAPPING"."JOBS" j WHERE j."REMAP_RUN_ID"=r."ID" AND j."STATUS" IN ('PENDING','PROCESSING')) AS pending
            FROM "MAPPING"."REMAP_RUNS" r
            """;

    public RemapRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long create(RemapRequest request, long configId, long upperId, String creator) {
        return jdbc.queryForObject(
                """
                INSERT INTO "MAPPING"."REMAP_RUNS" ("SCOPE_KIND","SCOPE_VALUE","CONFIG_ID","UPPER_EXTRACTION_ID","CREATED_BY")
                VALUES (?,?::jsonb,?,?,?) RETURNING "ID"
                """,
                Long.class,
                request.scope(),
                json.writeValueAsString(request),
                configId,
                upperId,
                creator);
    }

    public Optional<RemapRun> find(long id) {
        return jdbc.query(PROGRESS + " WHERE r.\"ID\"=?", (rs, n) -> row(rs), id).stream()
                .findFirst();
    }

    public List<RemapRun> recent(int limit) {
        return jdbc.query(PROGRESS + " ORDER BY r.\"ID\" DESC LIMIT ?", (rs, n) -> row(rs), limit);
    }

    public List<Long> running() {
        return jdbc.queryForList(
                "SELECT \"ID\" FROM \"MAPPING\".\"REMAP_RUNS\" WHERE \"STATE\"='RUNNING' ORDER BY \"ID\"",
                Long.class);
    }

    public boolean hasIssueCode(String code) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                SELECT EXISTS (SELECT 1 FROM "MAPPING"."RESULTS"
                WHERE jsonb_path_exists("RESULT", '$.**.issues[*] ? (@.code == $code)',
                  jsonb_build_object('code',CAST(? AS text))))
                """,
                        Boolean.class,
                        code));
    }

    public boolean lock(long id) {
        return !jdbc.queryForList(
                        "SELECT \"ID\" FROM \"MAPPING\".\"REMAP_RUNS\" WHERE \"ID\"=? FOR UPDATE",
                        Long.class,
                        id)
                .isEmpty();
    }

    public void state(long id, String state) {
        jdbc.update(
                "UPDATE \"MAPPING\".\"REMAP_RUNS\" SET \"STATE\"=?,\"UPDATED_AT\"=clock_timestamp() WHERE \"ID\"=? AND \"STATE\"<>'DONE'",
                state,
                id);
    }

    public void advance(long id, long cursor, int selected, int queued, boolean done) {
        jdbc.update(
                """
                UPDATE "MAPPING"."REMAP_RUNS" SET "CURSOR_EXTRACTION_ID"=?,
                  "SELECTED"="SELECTED"+?,"QUEUED"="QUEUED"+?,"SKIPPED"="SKIPPED"+?,
                  "STATE"=CASE WHEN ? THEN 'DONE' ELSE 'RUNNING' END,
                  "FINISHED_AT"=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,"UPDATED_AT"=clock_timestamp()
                WHERE "ID"=?
                """,
                cursor,
                selected,
                queued,
                selected - queued,
                done,
                done,
                id);
    }

    /** Same extraction population and ID bounds as OcrExtractionReader.scanAfter; only scope filters differ. */
    public List<Long> select(RemapRun run, int limit) {
        String filter = "";
        var args = new ArrayList<Object>();
        args.add(run.cursorExtractionId());
        args.add(run.upperExtractionId());
        var scope = run.scopeValue();
        switch (run.scope()) {
            case "BATCH" -> {
                filter =
                        " AND EXISTS (SELECT 1 FROM \"INGESTION\".\"IMPORT_BATCHES\" b WHERE b.\"ID\"=d.\"BATCH_ID\" AND b.\"ID\"=?)";
                args.add(scope.batchId());
            }
            case "DATE_RANGE" -> {
                filter = " AND d.\"CREATED_AT\">=? AND d.\"CREATED_AT\"<?";
                args.add(
                        java.sql.Timestamp.from(
                                scope.from().atStartOfDay(IMPORT_ZONE).toInstant()));
                args.add(
                        java.sql.Timestamp.from(
                                scope.to().plusDays(1).atStartOfDay(IMPORT_ZONE).toInstant()));
            }
            case "ISSUE" -> {
                filter =
                        """
                    AND jsonb_path_exists((
                      SELECT r."RESULT" FROM "MAPPING"."RESULTS" r
                      JOIN "MAPPING"."JOBS" j ON j."ID"=r."JOB_ID"
                      WHERE j."EXTRACTION_ID"=e."ID" ORDER BY r."ID" DESC LIMIT 1
                    ), '$.**.issues[*] ? (@.code == $code)', jsonb_build_object('code',CAST(? AS text)))
                    """;
                args.add(scope.issueCode());
            }
            default -> {}
        }
        args.add(limit);
        return jdbc.queryForList(
                """
                SELECT e."ID" FROM "INGESTION"."EXTRACTIONS" e
                JOIN "INGESTION"."DOCUMENTS" d ON d."ID"=e."CV_DOCUMENT_ID"
                WHERE e."ID">? AND e."ID"<=?
                """
                        + filter
                        + " ORDER BY e.\"ID\" LIMIT ?",
                Long.class,
                args.toArray());
    }

    private RemapRun row(ResultSet rs) throws SQLException {
        var finished = rs.getTimestamp("FINISHED_AT");
        return new RemapRun(
                rs.getLong("ID"),
                rs.getString("SCOPE_KIND"),
                json.readValue(rs.getString("SCOPE_VALUE"), RemapRequest.class),
                rs.getLong("CONFIG_ID"),
                rs.getString("STATE"),
                rs.getLong("CURSOR_EXTRACTION_ID"),
                rs.getLong("UPPER_EXTRACTION_ID"),
                rs.getLong("SELECTED"),
                rs.getLong("QUEUED"),
                rs.getLong("SKIPPED"),
                rs.getLong("completed"),
                rs.getLong("failed"),
                rs.getLong("pending"),
                rs.getString("CREATED_BY"),
                rs.getTimestamp("CREATED_AT").toInstant(),
                rs.getTimestamp("UPDATED_AT").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
