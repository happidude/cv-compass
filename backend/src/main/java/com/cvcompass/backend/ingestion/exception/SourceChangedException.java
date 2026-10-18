package com.cvcompass.backend.ingestion.exception;

/** A producer changed the source snapshot; defer without consuming an import attempt. */
public class SourceChangedException extends RuntimeException {
    public SourceChangedException() { super("Source changed during observation"); }
}
