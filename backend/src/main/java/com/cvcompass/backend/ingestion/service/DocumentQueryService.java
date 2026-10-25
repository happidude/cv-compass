package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.PaginationProperties;
import com.cvcompass.backend.ingestion.dto.DocumentCursorPage;
import com.cvcompass.backend.ingestion.dto.DocumentDetailRow;
import com.cvcompass.backend.ingestion.dto.DocumentDetails;
import com.cvcompass.backend.ingestion.dto.DocumentOcrResult;
import com.cvcompass.backend.ingestion.dto.DocumentOcrResult.OcrIssue;
import com.cvcompass.backend.ingestion.dto.DocumentOcrResult.OcrPage;
import com.cvcompass.backend.ingestion.exception.DocumentNotFoundException;
import com.cvcompass.backend.ingestion.enums.OcrProtocol;
import com.cvcompass.backend.ingestion.repository.DocumentQueryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DocumentQueryService {
    private final DocumentQueryRepository repository;
    private final PaginationProperties properties;
    private final ObjectMapper json;

    public DocumentQueryService(DocumentQueryRepository repository, PaginationProperties properties,
                                ObjectMapper json) {
        this.repository = repository;
        this.properties = properties;
        this.json = json;
    }

    public DocumentCursorPage list(Long beforeId, Integer requestedSize) {
        int size = requestedSize == null ? properties.getDefaultPageSize() : requestedSize;
        if (size < 1 || size > properties.getMaxPageSize() || beforeId != null && beforeId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var rows = repository.list(beforeId, size + 1);
        boolean hasNext = rows.size() > size;
        var content = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        Long next = hasNext ? content.getLast().metadata().id() : null;
        return new DocumentCursorPage(content, size, hasNext, next);
    }

    public DocumentDetails find(long id) {
        var row = repository.find(id).orElseThrow(DocumentNotFoundException::new);
        return DocumentDetails.from(row.overview(), result(row));
    }

    private DocumentOcrResult result(DocumentDetailRow row) {
        String availability = row.overview().processing().resultAvailability();
        if (row.resultJson() == null) {
            return new DocumentOcrResult(availability, row.extractionId(), null, null,
                    List.of(), List.of(), List.of(), row.legacyRawText());
        }
        JsonNode result;
        try { result = json.readTree(row.resultJson()); }
        catch (RuntimeException malformed) { throw unavailable(); }
        if (result == null || !result.isObject() || result.path("pages").isMissingNode()
                || !result.path("pages").isArray() || !result.path("warnings").isArray()
                || !result.path("errors").isArray() || !result.path("status").isString()
                || !List.of("success","partial","failed").contains(result.path("status").asString())
                || result.path("pages").size() > OcrProtocol.MAX_PAGES) throw unavailable();
        String version = result.path("schema_version").asString();
        if (!OcrProtocol.LEGACY_RESULT_VERSION.equals(version) && !OcrProtocol.RESULT_VERSION.equals(version))
            throw unavailable();
        var pages = new ArrayList<OcrPage>();
        for (JsonNode page : result.get("pages")) {
            if (!page.isObject() || !page.path("raw_text").isString()
                    || !page.path("page_number").isIntegralNumber() || !page.path("status").isString()
                    || !page.path("method").isString() || !page.path("warnings").isArray()
                    || !page.has("error") || OcrProtocol.LEGACY_RESULT_VERSION.equals(version)
                    && !page.path("text").isString() || OcrProtocol.RESULT_VERSION.equals(version)
                    && page.has("text")) throw unavailable();
            String pageStatus = page.get("status").asString(), method = page.get("method").asString();
            String rawText = page.get("raw_text").asString();
            if (page.get("page_number").asInt() != pages.size()+1
                    || rawText.codePointCount(0,rawText.length()) > OcrProtocol.MAX_TEXT_CODE_POINTS
                    || !(pageStatus.equals("ok") && List.of("native","ocr").contains(method)
                        && !rawText.isBlank() && page.get("error").isNull()
                    || pageStatus.equals("blank") && method.equals("none") && rawText.isEmpty()
                        && page.get("error").isNull()
                    || List.of("failed","not_processed").contains(pageStatus) && method.equals("none")
                        && !page.get("error").isNull()))
                throw unavailable();
            pages.add(new OcrPage(page.get("page_number").asInt(), page.get("status").asString(),
                    page.get("method").asString(), page.get("raw_text").asString(),
                    strings(page.get("warnings")), issue(page.get("error"))));
        }
        var errors = new ArrayList<OcrIssue>();
        for (JsonNode error : result.get("errors")) {
            OcrIssue value = issue(error);
            if (value == null) throw unavailable();
            errors.add(value);
        }
        JsonNode count = result.get("page_count");
        if (count == null || !(count.isNull() && pages.isEmpty() && result.get("status").asString().equals("failed")
                || count.isIntegralNumber() && count.asInt() >= 1 && count.asInt() == pages.size()))
            throw unavailable();
        boolean ok = pages.stream().anyMatch(page -> page.status().equals("ok"));
        boolean incomplete = pages.stream().anyMatch(page -> List.of("failed","not_processed").contains(page.status()));
        String expectedStatus = !ok ? "failed" : incomplete || !errors.isEmpty() ? "partial" : "success";
        if (!expectedStatus.equals(result.get("status").asString())) throw unavailable();
        return new DocumentOcrResult(availability, row.extractionId(), result.get("status").asString(),
                count.isNull() ? null : count.asInt(), List.copyOf(pages), strings(result.get("warnings")),
                List.copyOf(errors), null);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static OcrIssue issue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject() || !node.path("code").isString() || !node.path("stage").isString())
            throw unavailable();
        return new OcrIssue(node.get("code").asString(), node.get("stage").asString());
    }

    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        for (JsonNode value : node) {
            if (!value.isString()) throw unavailable();
            values.add(value.asString());
        }
        return List.copyOf(values);
    }
}
