package com.cvcompass.backend.candidate.dto;

import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record Profile(
        long id,
        long version,
        JsonNode basic,
        String basicState,
        String basicOrigin,
        JsonNode extensions,
        List<Experience> experiences,
        List<CandidateDocuments.Document> documents) {}
