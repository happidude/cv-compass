package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingOutcome;

record InProcessMappingProvider(String engineVersion, Mapper mapper) implements MappingProvider {
    @Override
    public MappingProviderKind kind() {
        return MappingProviderKind.IN_PROCESS;
    }

    @Override
    public MappingOutcome map(MappingClaim claim, Extraction input) {
        return mapper.map(claim, input);
    }

    @FunctionalInterface
    interface Mapper {
        MappingOutcome map(MappingClaim claim, Extraction input);
    }
}
