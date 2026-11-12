package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingOutcome;

interface MappingProvider {
    String engineVersion();

    MappingProviderKind kind();

    MappingOutcome map(MappingClaim claim, Extraction input);
}
