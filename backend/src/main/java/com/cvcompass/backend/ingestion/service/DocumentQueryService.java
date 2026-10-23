package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.PaginationProperties;
import com.cvcompass.backend.ingestion.dto.DocumentCursorPage;
import com.cvcompass.backend.ingestion.dto.DocumentDetailRow;
import com.cvcompass.backend.ingestion.dto.DocumentDetails;
import com.cvcompass.backend.ingestion.dto.DocumentOcrResult;
import com.cvcompass.backend.ingestion.dto.DocumentOcrResult.OcrIssue;
import com.cvcompass.backend.ingestion.dto.DocumentOcrResult.OcrPage;
import com.cvcompass.backend.ingestion.exception.DocumentNotFoundException;
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
        JsonNode result = json.readTree(row.resultJson());
        var pages = new ArrayList<OcrPage>();
        for (JsonNode page : result.get("pages")) {
            pages.add(new OcrPage(page.get("page_number").asInt(), page.get("status").asString(),
                    page.get("method").asString(), page.get("text").asString(), page.get("raw_text").asString(),
                    strings(page.get("warnings")), issue(page.get("error"))));
        }
        var errors = new ArrayList<OcrIssue>();
        for (JsonNode error : result.get("errors")) {
            errors.add(issue(error));
        }
        JsonNode count = result.get("page_count");
        return new DocumentOcrResult(availability, row.extractionId(), result.get("status").asString(),
                count.isNull() ? null : count.asInt(), List.copyOf(pages), strings(result.get("warnings")),
                List.copyOf(errors), null);
    }

    private static OcrIssue issue(JsonNode node) {
        return node == null || node.isNull() ? null
                : new OcrIssue(node.get("code").asString(), node.get("stage").asString());
    }

    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        for (JsonNode value : node) {
            values.add(value.asString());
        }
        return List.copyOf(values);
    }
}
