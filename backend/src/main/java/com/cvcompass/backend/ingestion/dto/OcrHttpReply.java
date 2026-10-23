package com.cvcompass.backend.ingestion.dto;

import java.util.Map;
public record OcrHttpReply(int status, Map<String,Object> data) { }
