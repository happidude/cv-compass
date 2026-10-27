package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingDefinition;
import com.cvcompass.backend.mapping.dto.MappingDefinition.Field;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MappingEngineTests {
    private final MappingEngine engine = new MappingEngine();

    private MappingClaim claim(String extractor, String argument) {
        return new MappingClaim(
                1,
                2,
                3,
                "synthetic-1",
                MappingEngine.VERSION,
                1,
                UUID.randomUUID(),
                Instant.MAX,
                new MappingDefinition(
                        1, List.of(new Field("sample", "STRING", "PAGE", extractor, argument))));
    }

    @Test
    void evidenceUsesCodePointsAndPreservesCombiningCharacters() {
        String raw = "😀 prefix e\u0301 Java\nother";
        var input =
                new Extraction(
                        2,
                        9,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(new Page(1, "ok", raw)));
        var result = engine.map(claim("LITERAL", "e\u0301 Java"), input);
        assertThat(result.values().getFirst().evidence())
                .isEqualTo(new Evidence(2, 1, 9, 16, "e\u0301 Java"));
        engine.validateEvidence(input, result.values().getFirst().evidence());
        assertThatThrownBy(
                        () ->
                                engine.validateEvidence(
                                        input, new Evidence(2, 1, 10, 17, "e\u0301 Java")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialUsesOnlyOkPagesAndRetainsOriginalPageNumber() {
        var input =
                new Extraction(
                        2,
                        9,
                        "cv-compass.ocr/2.0",
                        "partial",
                        false,
                        List.of(new Page(1, "failed", "Java"), new Page(2, "ok", "Java")));
        var result = engine.map(claim("LITERAL", "Java"), input);
        assertThat(result.incompleteSource()).isTrue();
        assertThat(result.quality()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.values().getFirst().evidence().page()).isEqualTo(2);
        assertThatThrownBy(() -> engine.validateEvidence(input, new Evidence(2, 1, 0, 4, "Java")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedLegacyAndNoUsablePagesHaveExplicitRejection() {
        var claim = claim("FIRST_LINE", null);
        assertThat(
                        engine.map(claim, new Extraction(2, 9, null, "legacy", true, List.of()))
                                .reason())
                .isEqualTo("LEGACY_UNPAGED");
        assertThat(
                        engine.map(
                                        claim,
                                        new Extraction(
                                                2,
                                                9,
                                                "cv-compass.ocr/2.0",
                                                "failed",
                                                false,
                                                List.of(new Page(1, "failed", ""))))
                                .reason())
                .isEqualTo("OCR_UNUSABLE");
        var blank =
                engine.map(
                        claim,
                        new Extraction(
                                2,
                                9,
                                "cv-compass.ocr/2.0",
                                "success",
                                false,
                                List.of(new Page(1, "blank", ""))));
        assertThat(blank.reason()).isEqualTo("NO_OK_PAGES");
        assertThat(blank.values()).isEmpty();
    }

    @Test
    void missingLiteralDoesNotInventValueAndFirstLineRetainsRawSpan() {
        var input =
                new Extraction(
                        2,
                        9,
                        "cv-compass.ocr/1.0",
                        "success",
                        false,
                        List.of(new Page(1, "ok", "  Synthetic 😀\r\nsecond")));
        assertThat(engine.map(claim("LITERAL", "absent"), input).values()).isEmpty();
        assertThat(engine.map(claim("FIRST_LINE", null), input).values().getFirst().value())
                .isEqualTo("  Synthetic 😀");
    }

    @Test
    void rejectsUnsupportedConfigurationAndDuplicateFields() {
        for (var field :
                List.of(
                        new Field("name", "SQL", "PAGE", "LITERAL", "x"),
                        new Field("name", "STRING", "DOCUMENT", "FIRST_LINE", null),
                        new Field("name", "STRING", "PAGE", "SCRIPT", "x"),
                        new Field("name", "STRING", "PAGE", "LITERAL", ""),
                        new Field("name", "STRING", "PAGE", "FIRST_LINE", "x"))) {
            assertThatThrownBy(() -> new MappingDefinition(1, List.of(field)).validate())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var field = new Field("same", "STRING", "PAGE", "FIRST_LINE", null);
        assertThatThrownBy(
                        () ->
                                new MappingDefinition(1, "incompatible-engine", List.of(field))
                                        .validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MappingDefinition(1, List.of(field, field)).validate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintIncludesOcrMetadataAndOutputHasSeparateBound() {
        var page = new Page(1, "ok", "Synthetic");
        var input =
                new Extraction(
                        2,
                        9,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(page),
                        "profile-1",
                        List.of());
        var changed =
                new Extraction(
                        2,
                        9,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(page),
                        "profile-2",
                        List.of());
        assertThat(engine.map(claim("FIRST_LINE", null), input).inputSha256())
                .isNotEqualTo(engine.map(claim("FIRST_LINE", null), changed).inputSha256());
        var oversized =
                new Extraction(
                        2,
                        9,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(new Page(1, "ok", "x".repeat(4097))));
        assertThatThrownBy(() -> engine.map(claim("FIRST_LINE", null), oversized))
                .hasMessage("Mapping value exceeds output limit");
    }

    @Test
    void recordsSyntheticTimingWithoutClaimingRealCvThroughput() {
        var pages = new java.util.ArrayList<Page>();
        for (int i = 1; i <= 50; i++) pages.add(new Page(i, "ok", "x".repeat(9996) + "Java"));
        var input = new Extraction(2, 9, "cv-compass.ocr/2.0", "success", false, pages);
        long start = System.nanoTime();
        var result = engine.map(claim("LITERAL", "Java"), input);
        long elapsed = System.nanoTime() - start;
        long heapUsed =
                java.lang.management.ManagementFactory.getMemoryMXBean()
                        .getHeapMemoryUsage()
                        .getUsed();
        long heapPoolPeakSum =
                java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
                        .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                        .mapToLong(pool -> pool.getPeakUsage().getUsed())
                        .sum();
        assertThat(result.values().getFirst().evidence().start()).isEqualTo(9996);
        System.out.printf(
                "MAPPING_SYNTHETIC_METRICS pages=50 code_points=500000 elapsed_ns=%d heap_used_bytes=%d heap_pool_peak_sum_bytes=%d%n",
                elapsed, heapUsed, heapPoolPeakSum);
    }
}
