package com.cvcompass.backend.ingestion.batch;

public class BatchNotFoundException extends RuntimeException {
    public BatchNotFoundException() { super("Batch not found"); }
}
