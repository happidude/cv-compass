package com.cvcompass.backend.mapping.api;

import com.cvcompass.backend.mapping.repository.CompletedMappingRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CompletedMappings {
    private final CompletedMappingRepository repository;

    public CompletedMappings(CompletedMappingRepository repository) {
        this.repository = repository;
    }

    public long upperId() {
        return repository.upperId();
    }

    public List<Long> scan(long after, long upper, int limit) {
        if (after < 0 || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid publication page");
        return repository.scan(after, upper, limit);
    }

    public Optional<Result> read(long id) {
        return repository.read(id);
    }

    public record Result(
            long id,
            int schemaVersion,
            String quality,
            String body,
            long extractionId,
            long configId,
            long documentId) {}
}
