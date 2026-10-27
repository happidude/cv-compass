package com.cvcompass.backend.ingestion.dto;

import java.util.UUID;

public record OcrReceipt(long jobId, UUID attemptId, Long extractionId, boolean replay) {}
