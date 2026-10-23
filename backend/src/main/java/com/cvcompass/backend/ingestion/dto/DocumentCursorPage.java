package com.cvcompass.backend.ingestion.dto;

import java.util.List;

public record DocumentCursorPage(List<DocumentOverview> content, int size, boolean hasNext, Long nextBeforeId) {
}
