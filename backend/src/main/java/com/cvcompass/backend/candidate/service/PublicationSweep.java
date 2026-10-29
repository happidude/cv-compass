package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.mapping.api.CompletedMappings;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationSweep {
    private final JdbcTemplate jdbc;
    private final CompletedMappings mappings;

    public PublicationSweep(JdbcTemplate jdbc, CompletedMappings mappings) {
        this.jdbc = jdbc;
        this.mappings = mappings;
    }

    /** Reserve bounded metadata only. Crash/failure is recovered by the next bounded reconciliation sweep. */
    @Transactional
    public List<Long> reserve(int size) {
        var state =
                jdbc.queryForMap(
                        "SELECT \"AFTER_ID\",\"UPPER_ID\" FROM \"CANDIDATE_PUBLICATION_SETTINGS\" WHERE \"ID\"=1 FOR UPDATE");
        long after = ((Number) state.get("AFTER_ID")).longValue(),
                upper =
                        state.get("UPPER_ID") == null
                                ? mappings.upperId()
                                : ((Number) state.get("UPPER_ID")).longValue();
        var ids = mappings.scan(after, upper, size);
        boolean done = ids.size() < size || !ids.isEmpty() && ids.getLast() == upper;
        jdbc.update(
                "UPDATE \"CANDIDATE_PUBLICATION_SETTINGS\" SET \"AFTER_ID\"=?,\"UPPER_ID\"=? WHERE \"ID\"=1",
                done ? 0 : ids.getLast(),
                done ? null : upper);
        return ids;
    }
}
