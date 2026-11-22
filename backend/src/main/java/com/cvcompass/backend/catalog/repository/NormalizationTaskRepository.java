package com.cvcompass.backend.catalog.repository;

import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Code;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Item;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NormalizationTaskRepository {
    private final JdbcTemplate jdbc;
    private final NormalizationProperties properties;

    public NormalizationTaskRepository(JdbcTemplate jdbc, NormalizationProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record Task(long id, String kind, String folded, String raw, Instant expiresAt) {}

    public void enqueue(String kind, String folded, String raw, String company) {
        // Existing leases need no write lock while the caller holds a candidate profile lock.
        jdbc.update(
                """
                INSERT INTO "CATALOG"."ALIASES"
                  ("KIND","ALIAS_FOLDED","ALIAS_RAW","CONTEXT_COMPANY","STATE","MAX_ATTEMPTS")
                SELECT ?,?,?,?,'PENDING',? WHERE NOT EXISTS (
                  SELECT 1 FROM "CATALOG"."ALIASES" WHERE "KIND"=? AND "ALIAS_FOLDED"=?
                )
                ON CONFLICT ("KIND","ALIAS_FOLDED") DO NOTHING
                """,
                kind,
                folded,
                raw,
                company,
                properties.getMaxAttempts(),
                kind,
                folded);
    }

    public List<Item> claim(int max, UUID token, Instant now, Instant expires) {
        // Exhausted expired leases must become terminal even when their worker never submits.
        jdbc.update(
                """
                WITH exhausted AS (
                  SELECT "ID" FROM "CATALOG"."ALIASES"
                  WHERE "STATE"='LEASED' AND "LEASE_EXPIRES_AT"<=? AND "ATTEMPTS">="MAX_ATTEMPTS"
                  FOR UPDATE SKIP LOCKED
                ) UPDATE "CATALOG"."ALIASES" t
                SET "STATE"='FAILED',"TOKEN"=NULL,"LEASE_EXPIRES_AT"=NULL,
                  "COMPLETED_AT"=?,"ERROR_CODE"='LEASE_EXPIRED'
                FROM exhausted e WHERE t."ID"=e."ID"
                """,
                Timestamp.from(now),
                Timestamp.from(now));
        return jdbc.query(
                """
                WITH available AS (
                  SELECT "ID" FROM "CATALOG"."ALIASES"
                  WHERE "ATTEMPTS"<"MAX_ATTEMPTS" AND "NEXT_ATTEMPT_AT"<=?
                    AND ("STATE"='PENDING' OR ("STATE"='LEASED' AND "LEASE_EXPIRES_AT"<=?))
                  ORDER BY "NEXT_ATTEMPT_AT","ID" LIMIT ? FOR UPDATE SKIP LOCKED
                ), claimed AS (
                  UPDATE "CATALOG"."ALIASES" t
                  SET "STATE"='LEASED',"TOKEN"=?,"LEASE_EXPIRES_AT"=?,"ATTEMPTS"="ATTEMPTS"+1
                  FROM available a WHERE t."ID"=a."ID"
                  RETURNING t.*
                ) SELECT "ID","KIND","ALIAS_RAW","CONTEXT_COMPANY" FROM claimed
                  ORDER BY "NEXT_ATTEMPT_AT","ID"
                """,
                (rs, row) ->
                        new Item(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                Timestamp.from(now),
                Timestamp.from(now),
                max,
                token,
                Timestamp.from(expires));
    }

    public List<Task> lockLease(UUID token) {
        return jdbc.query(
                """
                SELECT "ID","KIND","ALIAS_FOLDED","ALIAS_RAW","LEASE_EXPIRES_AT"
                FROM "CATALOG"."ALIASES"
                WHERE "TOKEN"=? AND "STATE"='LEASED' ORDER BY "ID" FOR UPDATE
                """,
                (rs, row) ->
                        new Task(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getTimestamp(5).toInstant()),
                token);
    }

    public void done(long id, long entryId, Instant now) {
        jdbc.update(
                """
                UPDATE "CATALOG"."ALIASES" SET "ENTRY_ID"=?,"ORIGIN"='AI',"STATE"='DONE',"TOKEN"=NULL,
                  "LEASE_EXPIRES_AT"=NULL,"ERROR_CODE"=NULL,"COMPLETED_AT"=? WHERE "ID"=?
                """,
                entryId,
                Timestamp.from(now),
                id);
    }

    public void retry(long id, String code, Instant now, long backoffMillis) {
        jdbc.update(
                """
                UPDATE "CATALOG"."ALIASES"
                SET "STATE"=CASE WHEN "ATTEMPTS">="MAX_ATTEMPTS" THEN 'FAILED' ELSE 'PENDING' END,
                  "TOKEN"=NULL,"LEASE_EXPIRES_AT"=NULL,"ERROR_CODE"=?,
                  "NEXT_ATTEMPT_AT"=CAST(? AS timestamptz) + (? * "ATTEMPTS") * interval '1 millisecond',
                  "COMPLETED_AT"=CASE WHEN "ATTEMPTS">="MAX_ATTEMPTS" THEN CAST(? AS timestamptz) ELSE NULL END
                WHERE "ID"=?
                """,
                code,
                Timestamp.from(now),
                backoffMillis,
                Timestamp.from(now),
                id);
    }

    public Map<String, List<Code>> taxonomy() {
        var result = new LinkedHashMap<String, List<Code>>();
        for (String kind : List.of("FUNCTION", "SENIORITY", "INDUSTRY", "SCOPE")) {
            result.put(
                    kind,
                    jdbc.query(
                            """
                    SELECT "CODE","LABEL_VI" FROM "CATALOG"."TAXONOMY_CODES"
                    WHERE "KIND"=? AND "ACTIVE" ORDER BY "CODE"
                    """,
                            (rs, row) -> new Code(rs.getString(1), rs.getString(2)),
                            kind));
        }
        return result;
    }
}
