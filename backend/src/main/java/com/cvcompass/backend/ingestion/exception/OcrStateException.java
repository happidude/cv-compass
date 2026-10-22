package com.cvcompass.backend.ingestion.exception;

import com.cvcompass.backend.ingestion.enums.OcrStateError;

/** Safe domain error; no HTTP/auth behavior until the next assigned milestone. */
public class OcrStateException extends RuntimeException {
    private final OcrStateError reason;
    public OcrStateException(OcrStateError reason) { super(reason.name()); this.reason = reason; }
    public OcrStateError reason() { return reason; }
}
