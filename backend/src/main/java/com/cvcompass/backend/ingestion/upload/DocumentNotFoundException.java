package com.cvcompass.backend.ingestion.upload;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException() {
        super("Document was not found");
    }
}
