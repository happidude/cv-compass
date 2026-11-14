package com.cvcompass.backend.mapping.api;

import com.cvcompass.backend.mapping.dto.ExperienceMappingResult;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import com.cvcompass.backend.mapping.repository.CompletedMappingRepository;
import com.cvcompass.backend.mapping.service.ExperienceEngine;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CompletedMappings {
    private final CompletedMappingRepository repository;
    private final ObjectMapper json;

    public CompletedMappings(CompletedMappingRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
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

    /** Separates unsupported versions from malformed envelopes without exposing internal DTOs. */
    public boolean supportsPublicationVersion(Result result) {
        try {
            var body = json.readTree(result.body());
            if (!body.path("schemaVersion").isIntegralNumber()
                    || body.path("schemaVersion").asInt() != result.schemaVersion()
                    || !body.path("engineVersion").isString()) return false;
            String engine = body.path("engineVersion").asString();
            return result.schemaVersion() == 2 && ExperienceEngine.VERSION.equals(engine)
                    || result.schemaVersion() == 3 && MappingVersions.personalResultEngine(engine);
        } catch (IllegalArgumentException | JacksonException invalid) {
            return false;
        }
    }

    public Optional<PublishableMapping> publishable(Result result) {
        if (!supportsPublicationVersion(result)) return Optional.empty();
        try {
            var parsed = MappingVersions.result(result.body(), json);
            if (parsed.schemaVersion() != result.schemaVersion()) return Optional.empty();
            if (parsed instanceof ExperienceMappingResult experience
                    && ExperienceEngine.VERSION.equals(experience.engineVersion()))
                return Optional.of(
                        new PublishableMapping(2, experience.engineVersion(), false, false));
            if (parsed instanceof PersonalInfoMappingResult personal)
                return Optional.of(
                        new PublishableMapping(
                                3,
                                personal.engineVersion(),
                                true,
                                MappingVersions.personalResultFields(personal.engineVersion())
                                        .contains("dateOfBirth")));
            return Optional.empty();
        } catch (IllegalArgumentException | JacksonException invalid) {
            return Optional.empty();
        }
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
