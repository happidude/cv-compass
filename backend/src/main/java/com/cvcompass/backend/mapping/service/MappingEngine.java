package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingResult;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.MappingResult.Value;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MappingEngine {
    public static final String VERSION =
            com.cvcompass.backend.mapping.dto.MappingDefinition.ENGINE_VERSION;

    public MappingResult map(MappingClaim claim, Extraction input) {
        if (claim.extractionId() != input.id()
                || !VERSION.equals(claim.engineVersion())
                || claim.definition() == null
                || !VERSION.equals(claim.definition().engineCompatibility())) {
            throw new IllegalArgumentException("Mapping input identity mismatch");
        }
        claim.definition().validate();
        boolean incomplete =
                !"success".equals(input.status())
                        || input.pages().stream()
                                .anyMatch(
                                        p ->
                                                List.of("failed", "not_processed")
                                                        .contains(p.status()));
        String rejection =
                input.legacy()
                        ? "LEGACY_UNPAGED"
                        : !List.of("success", "partial").contains(input.status())
                                ? "OCR_UNUSABLE"
                                : input.pages().stream().noneMatch(p -> "ok".equals(p.status()))
                                        ? "NO_OK_PAGES"
                                        : null;
        var values = new ArrayList<Value>();
        if (rejection == null) {
            for (var field : claim.definition().fields()) {
                for (var page : input.pages()) {
                    if (!"ok".equals(page.status())) continue;
                    String raw = page.rawText();
                    int start = 0;
                    int end;
                    if ("LITERAL".equals(field.extractor())) {
                        start = raw.indexOf(field.argument());
                        if (start < 0) continue;
                        end = start + field.argument().length();
                    } else {
                        end = 0;
                        while (end < raw.length()
                                && raw.charAt(end) != '\n'
                                && raw.charAt(end) != '\r') end++;
                        if (raw.substring(0, end).isBlank()) continue;
                    }
                    String text = raw.substring(start, end);
                    if (text.codePointCount(0, text.length()) > 4096) {
                        throw new IllegalArgumentException("Mapping value exceeds output limit");
                    }
                    int cpStart = raw.codePointCount(0, start);
                    int cpEnd = raw.codePointCount(0, end);
                    var evidence = new Evidence(input.id(), page.number(), cpStart, cpEnd, text);
                    validateEvidence(input, evidence);
                    values.add(new Value(field.key(), text, evidence));
                    break;
                }
            }
        }
        return new MappingResult(
                1,
                input.id(),
                input.schemaVersion(),
                claim.configId(),
                claim.configRevision(),
                claim.engineVersion(),
                rejection != null ? "UNSUPPORTED_INPUT" : "NEEDS_REVIEW",
                incomplete,
                rejection,
                values,
                MappingDigests.sha256(MappingDigests.canonical(input)),
                input.documentId(),
                input.profileRevision());
    }

    public void validateEvidence(Extraction input, Evidence evidence) {
        var page =
                input.pages().stream()
                        .filter(p -> p.number() == evidence.page() && "ok".equals(p.status()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Invalid evidence page"));
        String raw = page.rawText();
        int count = raw.codePointCount(0, raw.length());
        if (evidence.extractionId() != input.id()
                || evidence.start() < 0
                || evidence.end() <= evidence.start()
                || evidence.end() > count
                || !raw.substring(
                                raw.offsetByCodePoints(0, evidence.start()),
                                raw.offsetByCodePoints(0, evidence.end()))
                        .equals(evidence.text())) {
            throw new IllegalArgumentException("Invalid evidence span");
        }
    }
}
