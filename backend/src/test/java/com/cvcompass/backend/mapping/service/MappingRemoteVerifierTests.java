package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.mapping.api.CompletedMappings;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.remote.MappingRemoteException;
import com.cvcompass.backend.mapping.remote.MappingRemoteJson;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class MappingRemoteVerifierTests {
    private final MappingRemoteVerifier verifier = new MappingRemoteVerifier();
    private final String hash =
            MappingDigests.sha256(MappingDigests.canonical(MappingRemoteFixtures.input()));

    private com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult verify(ObjectNode body) {
        return verifier.verify(
                MappingRemoteFixtures.claim(),
                MappingRemoteFixtures.input(),
                hash,
                body,
                MappingRemoteFixtures.provenance());
    }

    @Test
    void buildsServerOwnedPublishableV3WithCodePointEvidenceAndUnsupportedGender() {
        var result = verify(MappingRemoteFixtures.result(hash));
        assertThat(result.schemaVersion()).isEqualTo(3);
        assertThat(result.quality()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.extractionId()).isEqualTo(11);
        assertThat(result.documentId()).isEqualTo(12);
        assertThat(result.personalInfo().get("fullName").evidence().getFirst().start())
                .isEqualTo(2);
        assertThat(result.personalInfo().get("gender").normalized()).isNull();
        assertThat(result.personalInfo().get("gender").issues())
                .extracting("code")
                .containsExactly("GENDER_UNSUPPORTED");
        assertThat(result.values().getFirst().evidence().text()).isEqualTo("e\u0301");
        assertThat(result.experiences().getFirst().company().normalized()).isNull();
        assertThat(result.experiences().getFirst().period().start().month()).isEqualTo(1);
        var api = new CompletedMappings(null, MappingRemoteFixtures.JSON);
        var published =
                api.publishable(
                        new CompletedMappings.Result(
                                1,
                                3,
                                result.quality(),
                                MappingDigests.canonical(result),
                                11,
                                13,
                                12));
        assertThat(published).isPresent();
        assertThat(
                        MappingVersions.result(
                                MappingDigests.canonical(result), MappingRemoteFixtures.JSON))
                .isEqualTo(result);
    }

    @Test
    void rejectsUtf16OffsetsChangedEvidenceTextAndFabricatedString() {
        for (String mode : List.of("offset", "text", "value")) {
            var body = MappingRemoteFixtures.result(hash);
            var field = (ObjectNode) body.path("personal").path("fullName");
            var evidence = (ObjectNode) field.path("evidence");
            if (mode.equals("offset")) evidence.put("start", 3);
            if (mode.equals("text")) evidence.put("text", "Synthetic Other");
            if (mode.equals("value")) field.put("value", "Synthetic Other");
            assertThatThrownBy(() -> verify(body))
                    .isInstanceOf(MappingRemoteException.class)
                    .hasMessage("INVALID_EVIDENCE");
        }
    }

    @Test
    void rejectsBadDatesUnknownKeysMissingKeysAndWrongProvenance() {
        for (String mode :
                List.of("month", "year", "order", "ongoing", "extra", "missing", "provenance")) {
            var body = MappingRemoteFixtures.result(hash);
            var row = (ObjectNode) body.path("experiences").get(0);
            switch (mode) {
                case "month" -> row.put("start", "2020-13");
                case "year" -> row.put("start", "1800");
                case "order" -> row.put("start", "2025");
                case "ongoing" -> row.put("ongoing", true);
                case "extra" -> body.put("documentId", 100);
                case "missing" -> ((ObjectNode) body.path("personal")).remove("phone");
                case "provenance" ->
                        ((ObjectNode) body.path("provenance")).put("model", "different");
            }
            assertThatThrownBy(() -> verify(body)).hasMessage("INVALID_RESULT");
        }
    }

    @Test
    void compactParserRejectsDuplicatesTrailingDataAndLongStrings() {
        for (String raw :
                List.of("{\"a\":1,\"a\":2}", "{} {}", "{\"a\":\"" + "x".repeat(2001) + "\"}"))
            assertThatThrownBy(() -> MappingRemoteJson.parse(raw, MappingRemoteFixtures.JSON))
                    .hasMessage("INVALID_RESULT");
    }

    @Test
    void remoteProviderCannotRunLocally() {
        var provider = new RemoteMappingProvider();
        assertThat(provider.kind()).isEqualTo(MappingProviderKind.REMOTE);
        assertThatThrownBy(
                        () ->
                                provider.map(
                                        MappingRemoteFixtures.claim(),
                                        MappingRemoteFixtures.input()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void workerIssuesOnlyReferenceApprovedFieldsAndCannotImpersonateServerIssues() {
        var fields =
                new java.util.ArrayList<String>(
                        com.cvcompass.backend.mapping.dto.PersonalInfoDefinition.V33_FIELD_NAMES);
        fields.add("experiences");
        fields.add("other");
        fields.add(null);
        for (String field : fields) {
            var body = MappingRemoteFixtures.result(hash);
            var issue = body.withArray("issues").addObject().put("code", "OTHER_TRUNCATED");
            if (field == null) issue.putNull("field");
            else issue.put("field", field);
            assertThat(verify(body).issues()).extracting("code").containsExactly("OTHER_TRUNCATED");
        }
        for (String field :
                List.of("documentId", "other:synthetic", "source", "", "personal.fullName")) {
            var body = MappingRemoteFixtures.result(hash);
            body.withArray("issues")
                    .addObject()
                    .put("code", "SYNTHETIC_WARNING")
                    .put("field", field);
            assertThatThrownBy(() -> verify(body)).hasMessage("INVALID_RESULT");
        }
        for (String code :
                List.of(
                        "FIELD_DISABLED",
                        "GENDER_UNSUPPORTED",
                        "VALUE_LENGTH_INVALID",
                        "UNRECOGNIZED_GENDER",
                        "INVALID_EMAIL",
                        "INVALID_PHONE",
                        "INVALID_NAME",
                        "LEGACY_UNPAGED",
                        "OCR_UNUSABLE",
                        "NO_OK_PAGES",
                        "INVALID_RESULT",
                        "INVALID_EVIDENCE",
                        "INPUT_CHANGED",
                        "UNSUPPORTED_PROTOCOL",
                        "LEASE_EXPIRED")) {
            var body = MappingRemoteFixtures.result(hash);
            body.withArray("issues").addObject().put("code", code).putNull("field");
            assertThatThrownBy(() -> verify(body)).hasMessage("INVALID_RESULT");
        }
    }

    @Test
    void unexpectedRuntimeFailureDuringEnvelopeConstructionBecomesSafe422() {
        var input =
                org.mockito.Mockito.mock(
                        com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction.class);
        org.mockito.Mockito.when(input.pages())
                .thenThrow(new IllegalStateException("synthetic-private-error-marker"));
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        MappingRemoteFixtures.claim(),
                                        input,
                                        hash,
                                        MappingRemoteFixtures.result(hash),
                                        MappingRemoteFixtures.provenance()))
                .isInstanceOfSatisfying(
                        MappingRemoteException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(422);
                            assertThat(error.getCause()).isNull();
                        })
                .hasMessage("INVALID_RESULT");
    }
}
