package com.cvcompass.backend.ingestion.enums;

/** Fixed contract ceilings. Configuration may reduce these, never increase them. */
public final class OcrProtocol {
    public static final String LEGACY_RESULT_VERSION = "cv-compass.ocr/1.0";
    public static final String RESULT_VERSION = "cv-compass.ocr/2.0";
    public static final String JOB_VERSION = "cv-compass.ocr-job/1.0";
    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_PAGES = 50;
    public static final int MAX_TEXT_CODE_POINTS = 50_000;
    public static final int MAX_DOCUMENT_TEXT = 500_000;
    public static final int MAX_RESULT_BYTES = 8_388_608;
    public static final int MAX_ENVELOPE_BYTES = MAX_RESULT_BYTES + 65_536;
    private OcrProtocol() { }
}
