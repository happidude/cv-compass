package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.dto.OriginalPdfMetadata;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OriginalPdfService {
    private final JdbcTemplate jdbc;

    public OriginalPdfService(JdbcTemplate jdbc) {
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
