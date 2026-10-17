package com.cvcompass.backend.ingestion.exception;

public class BatchNotFoundException extends RuntimeException {
    public BatchNotFoundException() { super("Batch not found"); }
}
