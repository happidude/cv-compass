package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.ingestion.enums.OcrProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class StoredOcrExtractionReader implements OcrExtractionReader {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StoredOcrExtractionReader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Long> scanAfter(long afterId, long upperId, int limit) {
        if (afterId < 0 || upperId < 0 || limit < 1 || limit > MAX_QUERY_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid OCR scan bounds");
        }
        return jdbc.query(
                """
                SELECT "ID" FROM "INGESTION"."EXTRACTIONS" WHERE "ID">? AND "ID"<=? ORDER BY "ID" LIMIT ?
                """,
                (rs, n) -> rs.getLong(1),
                afterId,
                upperId,
                limit);
    }

    @Override
    public long upperId() {
        return jdbc.queryForObject(
                "SELECT COALESCE(MAX(\"ID\"),0) FROM \"INGESTION\".\"EXTRACTIONS\"", Long.class);
    }

    @Override
    public Optional<Extraction> find(long extractionId) {
        return jdbc
                .query(
                        """
                SELECT "ID","CV_DOCUMENT_ID","OCR_RESULT"::text,"PROFILE_REVISION" FROM "INGESTION"."EXTRACTIONS" WHERE "ID"=?
                """,
                        (rs, n) -> {
                            String stored = rs.getString(3);
                            if (stored == null) {
                                return new Extraction(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        null,
                                        "legacy",
                                        true,
                                        List.of());
                            }
                            JsonNode root = json.readTree(stored);
                            var pages = new ArrayList<Page>();
                            String version = root.path("schema_version").asString("");
                            if (!List.of("cv-compass.ocr/1.0", "cv-compass.ocr/2.0")
                                            .contains(version)
                                    || !root.path("pages").isArray()
                                    || root.path("pages").size() > OcrProtocol.MAX_PAGES) {
                                return new Extraction(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        version,
                                        "invalid",
                                        false,
                                        List.of());
                            }
                            long characters = 0;
                            for (JsonNode page : root.path("pages")) {
                                if (!page.path("page_number").isIntegralNumber()
                                        || page.path("page_number").asInt() != pages.size() + 1
                                        || !page.path("raw_text").isString()
                                        || !List.of("ok", "blank", "failed", "not_processed")
                                                .contains(page.path("status").asString(""))) {
                                    return new Extraction(
                                            rs.getLong(1),
                                            rs.getLong(2),
                                            version,
                                            "invalid",
                                            false,
                                            List.of());
                                }
                                pages.add(
                                        new Page(
                                                page.path("page_number").asInt(),
                                                page.path("status").asString(),
                                                page.path("raw_text").asString(),
                                                warnings(page.path("warnings"))));
                                String raw = page.path("raw_text").asString();
                                int count = raw.codePointCount(0, raw.length());
                                characters += count;
                                if (count > OcrProtocol.MAX_TEXT_CODE_POINTS
                                        || characters > 500000) {
                                    throw new IllegalArgumentException(
                                            "OCR input exceeds mapping limits");
                                }
                            }
                            return new Extraction(
                                    rs.getLong(1),
                                    rs.getLong(2),
                                    version,
                                    root.path("status").asString("invalid"),
                                    false,
                                    pages,
                                    rs.getString(4),
                                    warnings(root.path("warnings")));
                        },
                        extractionId)
                .stream()
                .findFirst();
    }

    private static List<String> warnings(JsonNode node) {
        var values = new ArrayList<String>();
        if (!node.isArray() || node.size() > 32) {
            throw new IllegalArgumentException("Invalid OCR warnings");
        }
        for (JsonNode value : node) {
            if (!value.isString()) throw new IllegalArgumentException("Invalid OCR warning");
            values.add(value.asString());
        }
        return List.copyOf(values);
    }
}
