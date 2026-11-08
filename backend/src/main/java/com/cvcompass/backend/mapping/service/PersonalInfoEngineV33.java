package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import org.springframework.stereotype.Service;

/** Explicitly routes jobs pinned to the 3.3 grammar. */
@Service
public class PersonalInfoEngineV33 {
    public static final String VERSION = PersonalInfoDefinition.GRAMMAR_V33_ENGINE_VERSION;

    public PersonalInfoMappingResult map(MappingClaim claim, Extraction input) {
        return PersonalInfoEngineV31.map(claim, input, VERSION);
    }
}
