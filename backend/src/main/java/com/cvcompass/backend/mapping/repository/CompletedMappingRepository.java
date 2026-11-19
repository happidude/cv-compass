package com.cvcompass.backend.mapping.repository;

import com.cvcompass.backend.mapping.api.CompletedMappings.Result;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded metadata scans; only one immutable result body is loaded on demand. */
@Repository
public class CompletedMappingRepository {
    private final JdbcTemplate jdbc;

    public CompletedMappingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long upperId() {
        return jdbc.queryForObject(
                "SELECT COALESCE(max(\"ID\"),0) FROM \"CV_MAPPING_RESULTS\"", Long.class);
    }

    /** Read only the source job identity for publication logging. */
    public Long jobId(long resultId) {
        return jdbc.queryForObject(
                "SELECT \"JOB_ID\" FROM \"CV_MAPPING_RESULTS\" WHERE \"ID\"=?",
                Long.class,
                resultId);
    }

    public List<Long> scan(long after, long upper, int limit) {
        return jdbc.queryForList(
                "SELECT r.\"ID\" FROM \"CV_MAPPING_RESULTS\" r JOIN \"CV_MAPPING_JOBS\" j ON j.\"ID\"=r.\"JOB_ID\" WHERE j.\"STATUS\"='COMPLETED' AND r.\"ID\">? AND r.\"ID\"<=? ORDER BY r.\"ID\" LIMIT ?",
                Long.class,
                after,
                upper,
                limit);
    }

    public Optional<Result> read(long id) {
        return jdbc
                .query(
                        """
   SELECT r."ID",r."SCHEMA_VERSION",r."QUALITY",r."RESULT"::text,j."EXTRACTION_ID",j."CONFIG_ID",e."CV_DOCUMENT_ID"
   FROM "CV_MAPPING_RESULTS" r JOIN "CV_MAPPING_JOBS" j ON j."ID"=r."JOB_ID"
   JOIN "CV_EXTRACTIONS" e ON e."ID"=j."EXTRACTION_ID"
   WHERE r."ID"=? AND j."STATUS"='COMPLETED'
   """,
                        (rs, n) ->
                                new Result(
                                        rs.getLong(1),
                                        rs.getInt(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getLong(5),
                                        rs.getLong(6),
                                        rs.getLong(7)),
                        id)
                .stream()
                .findFirst();
    }
}
