package com.cvcompass.backend.ingestion.dto;

import com.cvcompass.backend.ingestion.entity.CvImportItem;

public record BatchItem(int fileOrder, String displayName, Long documentId, String errorCode) {
    public static BatchItem from(CvImportItem item) {
        return new BatchItem(
                item.getFileOrder(),
                item.getDisplayName(),
                item.getDocument() == null ? null : item.getDocument().getId(),
                item.getErrorCode());
    }
}
