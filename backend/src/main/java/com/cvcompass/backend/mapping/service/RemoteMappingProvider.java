package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.LlmExtractionDefinition;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingOutcome;

final class RemoteMappingProvider implements MappingProvider {
    public String engineVersion() {
        return LlmExtractionDefinition.ENGINE_VERSION;
    }

    public MappingProviderKind kind() {
        return MappingProviderKind.REMOTE;
    }

    public MappingOutcome map(MappingClaim claim, Extraction input) {
        throw new IllegalStateException("Remote mapping cannot execute in process");
    }
}
