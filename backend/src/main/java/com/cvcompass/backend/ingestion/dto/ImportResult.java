package com.cvcompass.backend.ingestion.dto;

import java.util.List;

public record ImportResult(BatchDetails batch, List<BatchItem> items) { }
