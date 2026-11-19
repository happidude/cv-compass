package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.PersonalField;
import com.cvcompass.backend.mapping.remote.MappingRemoteException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class MappingRemoteVerifierSummaryTests {
    private final Extraction input =
            new Extraction(
                    11,
                    12,
                    "cv-compass.ocr/2.0",
                    "success",
                    false,
                    List.of(
                            new Page(1, "ok", MappingRemoteFixtures.RAW),
                            new Page(2, "ok", MappingRemoteFixtures.RAW)),
                    "synthetic-profile",
                    List.of());
    private final String hash = MappingDigests.sha256(MappingDigests.canonical(input));

    @Test
    void acceptsLegacySummaryObject() {
        var body = summary("Synthetic Reviewer", span(1, "Synthetic Reviewer"));
        var field = verify(body);
        assertThat(field.raw()).isEqualTo("Synthetic Reviewer");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.evidence()).containsExactly(expected(1, "Synthetic Reviewer"));
    }

    @Test
    void acceptsTwoSamePageSpansWithUnicodeWhitespaceAndCodePointOffsets() {
        var spans = MappingRemoteFixtures.JSON.createArrayNode();
        spans.add(span(1, "\uD83D\uDE00 Synthetic Person e\u0301"));
        spans.add(span(1, "Synthetic Reviewer"));
        String value = "\uD83D\uDE00\tSynthetic\u00a0Person e\u0301\nSynthetic Reviewer";
        var field = verify(summary(value, spans));
        assertThat(field.raw()).isEqualTo(value);
        assertThat(field.evidence())
                .containsExactly(
                        expected(1, "\uD83D\uDE00 Synthetic Person e\u0301"),
                        expected(1, "Synthetic Reviewer"));
    }

    @Test
    void acceptsTwoPagesWhenOffsetsRestartOnTheNextPage() {
        var spans = MappingRemoteFixtures.JSON.createArrayNode();
        spans.add(span(1, "Synthetic Reviewer"));
        spans.add(span(2, "Synthetic Person"));
        var field = verify(summary("Synthetic Reviewer Synthetic Person", spans));
        assertThat(field.evidence())
                .containsExactly(
                        expected(1, "Synthetic Reviewer"), expected(2, "Synthetic Person"));
    }

    @Test
    void acceptsOneAndEightSpansIncludingAdjacentRanges() {
        for (int count : List.of(1, 8)) {
            var spans = MappingRemoteFixtures.JSON.createArrayNode();
            var texts = new java.util.ArrayList<String>();
            String raw = MappingRemoteFixtures.RAW;
            for (int start = 0; start < count; start++) {
                String text =
                        raw.substring(
                                raw.offsetByCodePoints(0, start),
                                raw.offsetByCodePoints(0, start + 1));
                texts.add(text);
                spans.addObject()
                        .put("page", 1)
                        .put("start", start)
                        .put("end", start + 1)
                        .put("text", text);
            }
            var field = verify(summary(String.join(" ", texts), spans));
            assertThat(field.evidence()).hasSize(count);
            assertThat(field.evidence())
                    .extracting(Evidence::text)
                    .containsExactlyElementsOf(texts);
        }
    }

    @Test
    void rejectsMismatchedOrBlankSummaryValue() {
        for (String value : List.of("Synthetic Other", "", " \t\n")) {
            var spans = MappingRemoteFixtures.JSON.createArrayNode();
            spans.add(span(1, "Synthetic Person"));
            spans.add(span(1, "Synthetic Reviewer"));
            assertInvalidEvidence(summary(value, spans));
        }
    }

    @Test
    void rejectsEmptyAndNineElementArrays() {
        assertInvalidEvidence(
                summary("Synthetic Reviewer", MappingRemoteFixtures.JSON.createArrayNode()));
        var spans = MappingRemoteFixtures.JSON.createArrayNode();
        var texts = new java.util.ArrayList<String>();
        String raw = MappingRemoteFixtures.RAW;
        for (int start = 0; start < 9; start++) {
            String text =
                    raw.substring(
                            raw.offsetByCodePoints(0, start), raw.offsetByCodePoints(0, start + 1));
            texts.add(text);
            spans.addObject()
                    .put("page", 1)
                    .put("start", start)
                    .put("end", start + 1)
                    .put("text", text);
        }
        assertInvalidEvidence(summary(String.join(" ", texts), spans));
    }

    @Test
    void rejectsOverlappingAndDuplicateRanges() {
        for (String second : List.of("Person", "Synthetic Person")) {
            var spans = MappingRemoteFixtures.JSON.createArrayNode();
            spans.add(span(1, "Synthetic Person"));
            spans.add(span(1, second));
            assertInvalidEvidence(summary("Synthetic Person " + second, spans));
        }
    }

    @Test
    void rejectsReverseStartAndPageOrderEvenWhenValueFollowsArrayOrder() {
        for (int firstPage : List.of(1, 2)) {
            var spans = MappingRemoteFixtures.JSON.createArrayNode();
            spans.add(span(firstPage, "Synthetic Reviewer"));
            spans.add(span(1, "Synthetic Person"));
            assertInvalidEvidence(summary("Synthetic Reviewer Synthetic Person", spans));
        }
    }

    @Test
    void validatesEverySummarySpanAgainstRawOcr() {
        for (String mode : List.of("page", "offset", "text")) {
            var spans = MappingRemoteFixtures.JSON.createArrayNode();
            spans.add(span(1, "Synthetic Person"));
            var second = span(1, "Synthetic Reviewer");
            if (mode.equals("page")) second.put("page", 3);
            if (mode.equals("offset")) second.put("start", second.path("start").asInt() + 1);
            if (mode.equals("text")) second.put("text", "Synthetic Other");
            spans.add(second);
            assertInvalidEvidence(summary("Synthetic Person Synthetic Reviewer", spans));
        }
    }

    @Test
    void rejectsEvidenceArraysForEveryOtherPersonalField() {
        for (String name : PersonalInfoDefinition.V33_FIELD_NAMES) {
            if (name.equals("summary")) continue;
            var body = MappingRemoteFixtures.result(hash);
            var field = ((ObjectNode) body.path("personal")).putObject(name);
            field.put("value", "Synthetic Person");
            field.putArray("evidence").add(span(1, "Synthetic Person"));
            assertInvalidEvidence(body);
        }
    }

    private ObjectNode summary(String value, JsonNode evidence) {
        var body = MappingRemoteFixtures.result(hash);
        ((ObjectNode) body.path("personal"))
                .putObject("summary")
                .put("value", value)
                .set("evidence", evidence);
        return body;
    }

    private ObjectNode span(int page, String text) {
        return MappingRemoteFixtures.evidence(text).put("page", page);
    }

    private Evidence expected(int page, String text) {
        var span = span(page, text);
        return new Evidence(
                input.id(), page, span.path("start").asInt(), span.path("end").asInt(), text);
    }

    private PersonalField verify(ObjectNode body) {
        return new MappingRemoteVerifier()
                .verify(
                        MappingRemoteFixtures.claim(),
                        input,
                        hash,
                        body,
                        MappingRemoteFixtures.provenance())
                .personalInfo()
                .get("summary");
    }

    private void assertInvalidEvidence(ObjectNode body) {
        assertThatThrownBy(() -> verify(body))
                .isInstanceOfSatisfying(
                        MappingRemoteException.class,
                        error -> assertThat(error.status()).isEqualTo(422))
                .hasMessage("INVALID_EVIDENCE");
    }
}
