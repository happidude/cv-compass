package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.dto.OriginalPdfMetadata;
import com.cvcompass.backend.ingestion.repository.OriginalPdfRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class OriginalPdfService {
    private final OriginalPdfRepository repository;

    public OriginalPdfService(OriginalPdfRepository repository) {
        this.repository = repository;
    }

    public Optional<OriginalPdfMetadata> metadata(long id) {
        return repository.metadata(id);
    }
}
