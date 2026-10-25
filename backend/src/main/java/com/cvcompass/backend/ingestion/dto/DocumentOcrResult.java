package com.cvcompass.backend.ingestion.dto;

import java.util.List;

/** Read projection only: preserves page order/raw text, never synthesizes legacy pages. */
public record DocumentOcrResult(String availability, Long extractionId, String status, Integer pageCount,
                                List<OcrPage> pages, List<String> warnings, List<OcrIssue> errors,
                                String legacyRawText) {
    public record OcrIssue(String code, String stage) {
    }

    public record OcrPage(int pageNumber, String status, String method, String rawText,
                          List<String> warnings, OcrIssue error) {
    }
}
