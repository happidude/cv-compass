package com.cvcompass.backend.ingestion.exception;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException() {
        super("Document was not found");
    }
}
