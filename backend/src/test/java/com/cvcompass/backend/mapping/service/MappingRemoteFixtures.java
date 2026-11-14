package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.LlmExtractionDefinition;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic text includes both a supplementary character and a combining sequence. */
public final class MappingRemoteFixtures {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String RAW =
            "\uD83D\uDE00 Synthetic Person e\u0301\nOTHER\n1990-05\nMeadow Labs\nEngineer\n2020-01 2022-03";

    private MappingRemoteFixtures() {}

    public static LlmExtractionDefinition definition() {
        try (var stream =
                MappingRemoteFixtures.class.getResourceAsStream(
                        "/mapping/synthetic-llm-definition.json")) {
            return (LlmExtractionDefinition)
                    MappingVersions.config(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8), JSON);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static Extraction input() {
        return new Extraction(
                11,
                12,
                "cv-compass.ocr/2.0",
                "success",
                false,
                List.of(new Page(1, "ok", RAW)),
                "synthetic-profile",
                List.of());
    }

    public static MappingClaim claim() {
        return new MappingClaim(
                1,
                11,
                13,
                definition().revision(),
                LlmExtractionDefinition.ENGINE_VERSION,
                1,
                UUID.randomUUID(),
                Instant.MAX,
                definition());
    }

    public static ObjectNode provenance() {
        return JSON.createObjectNode()
                .put("model", "synthetic-model")
                .put("promptVersion", "synthetic-v1")
                .put("workerVersion", "synthetic-v1");
    }

    public static ObjectNode claimBody() {
        var node = JSON.createObjectNode().put("protocol", "cv-compass.llm-mapping/1.0");
        node.putArray("engineVersions").add(LlmExtractionDefinition.ENGINE_VERSION);
        node.set("provenance", provenance());
        return node;
    }

    public static ObjectNode evidence(String text) {
        int start = RAW.codePointCount(0, RAW.indexOf(text));
        return JSON.createObjectNode()
                .put("page", 1)
                .put("start", start)
                .put("end", start + text.codePointCount(0, text.length()))
                .put("text", text);
    }

    public static ObjectNode result(String hash) {
        var node =
                JSON.createObjectNode()
                        .put("protocol", "cv-compass.llm-mapping/1.0")
                        .put("inputSha256", hash);
        var personal = node.putObject("personal");
        for (String field :
                List.of(
                        "fullName",
                        "email",
                        "phone",
                        "location",
                        "summary",
                        "gender",
                        "dateOfBirth")) personal.putNull(field);
        personal.putObject("fullName")
                .put("value", "Synthetic Person")
                .set("evidence", evidence("Synthetic Person"));
        personal.putObject("gender").put("value", "OTHER").set("evidence", evidence("OTHER"));
        personal.putObject("dateOfBirth")
                .put("value", "1990-05")
                .set("evidence", evidence("1990-05"));
        var row =
                node.putArray("experiences")
                        .addObject()
                        .put("title", "Engineer")
                        .put("company", "Meadow Labs")
                        .put("start", "2020-01")
                        .put("end", "2022-03")
                        .put("ongoing", false);
        var evidence = row.putObject("evidence");
        evidence.set("title", evidence("Engineer"));
        evidence.set("company", evidence("Meadow Labs"));
        evidence.set("period", evidence("2020-01 2022-03"));
        node.putArray("other")
                .addObject()
                .put("label", "synthetic")
                .put("value", "e\u0301")
                .set("evidence", evidence("e\u0301"));
        node.putArray("issues");
        node.set("provenance", provenance());
        return node;
    }
}
