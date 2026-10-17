package com.cvcompass.backend.ingestion.batch;

public record BatchItem(int fileOrder, String displayName, Long documentId, String errorCode) {
    static BatchItem from(CvImportItem item) {
        return new BatchItem(item.getFileOrder(), item.getDisplayName(),
                item.getDocument() == null ? null : item.getDocument().getId(), item.getErrorCode());
    }
}
