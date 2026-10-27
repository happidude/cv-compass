package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.PartialDate;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

final class ExperienceFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static ExperienceDefinition config() {
        return JSON.readValue(resource("experience-config-v2.json"), ExperienceDefinition.class);
    }

    static List<Fixture> read(String name) {
        return Arrays.asList(JSON.readValue(resource(name), Fixture[].class));
    }

    static String resource(String name) {
        try (var stream = ExperienceFixtures.class.getResourceAsStream("/mapping/" + name)) {
            return new String(
                    java.util.Objects.requireNonNull(stream).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Synthetic fixture unavailable", failure);
        }
    }

    static MappingClaim claim(ExperienceDefinition config) {
        return new MappingClaim(
                1,
                2,
                3,
                "synthetic-v2",
                ExperienceEngine.VERSION,
                1,
                UUID.randomUUID(),
                Instant.MAX,
                config);
    }

    static Extraction input(Fixture fixture) {
        boolean partial = fixture.pages().stream().anyMatch(p -> !"ok".equals(p.status()));
        return new Extraction(
                2,
                4,
                "cv-compass.ocr/2.0",
                partial ? "partial" : "success",
                false,
                fixture.pages().stream()
                        .map(p -> new Page(p.number(), p.status(), p.text()))
                        .toList(),
                "synthetic-profile",
                List.of());
    }

    static String date(PartialDate value) {
        return value == null
                ? null
                : String.format(java.util.Locale.ROOT, "%04d", value.year())
                        + (value.month() == null ? "" : "-%02d".formatted(value.month()))
                        + (value.day() == null ? "" : "-%02d".formatted(value.day()))
                        + ":"
                        + value.precision();
    }

    static void verifySpans(ExperienceMappingResult result, Extraction input) {
        var validator = new MappingEngine();
        for (var issue : result.issues())
            for (var span : issue.evidence()) validator.validateEvidence(input, span);
        for (var experience : result.experiences()) {
            for (var span : experience.evidence()) validator.validateEvidence(input, span);
            for (var span : experience.company().evidence())
                validator.validateEvidence(input, span);
            for (var span : experience.title().evidence()) validator.validateEvidence(input, span);
            for (var span : experience.period().evidence()) validator.validateEvidence(input, span);
            for (var issue : experience.issues())
                for (var span : issue.evidence()) validator.validateEvidence(input, span);
        }
    }

    record Fixture(String id, String split, List<InputPage> pages, List<Expected> expected) {}

    record InputPage(int number, String status, String text) {}

    record Expected(
            String company,
            String title,
            String start,
            String end,
            String state,
            boolean ongoing) {}
}
