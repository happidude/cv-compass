package com.cvcompass.backend.ingestion.exception;

/** Fixed safe code only; never include request contents or credentials. */
public class OcrHttpException extends RuntimeException {
    private final int status;
    public OcrHttpException(int status, String code) { super(code); this.status=status; }
    public int status() { return status; }
}
