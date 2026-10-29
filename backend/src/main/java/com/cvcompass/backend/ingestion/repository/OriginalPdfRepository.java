package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.dto.OriginalPdfMetadata;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OriginalPdfRepository {
    private final JdbcTemplate jdbc;

    public OriginalPdfRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OriginalPdfMetadata> metadata(long id) {
        return jdbc
                .query(
                        "SELECT \"STORAGE_PATH\",\"FILE_SIZE\",\"MIME_TYPE\" FROM \"CV_DOCUMENTS\" WHERE \"ID\"=?",
                        (rs, n) ->
                                new OriginalPdfMetadata(
                                        rs.getString(1), rs.getLong(2), rs.getString(3)),
                        id)
                .stream()
                .findFirst();
    }
}
