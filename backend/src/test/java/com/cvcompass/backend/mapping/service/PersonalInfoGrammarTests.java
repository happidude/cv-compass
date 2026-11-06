package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PersonalInfoGrammarTests {
    @Test
    void oversizedUnlabelledHeaderContextAbstainsWithoutBlockingExplicitContact() throws Exception {
        String raw =
                "Personal Profile\n" + "Synthetic Header\n".repeat(64) + "Email: alex@example.test";
        var result = parse(raw);
        assertThat(result.personalInfo().get("fullName").state()).isEqualTo("NOT_FOUND");
        assertThat(result.personalInfo().get("fullName").alternatives()).isEmpty();
        assertThat(result.personalInfo().get("email").normalized()).isEqualTo("alex@example.test");
        assertThat(result.personalInfo().get("email").evidence()).hasSize(1);
        assertThat(result.personalInfo().get("email").evidence().getFirst().text())
                .isEqualTo("alex@example.test");
    }

    @Test
    void syntheticGrammarCasesCoverPositiveNegativeAndAmbiguousStructures() throws Exception {
        try (var stream =
                getClass()
                        .getResourceAsStream("/mapping/personal-info/grammar/bounded-cases.json")) {
            for (var test : JsonMapper.builder().build().readTree(stream)) {
                var value =
                        parse(test.path("raw").asString())
                                .personalInfo()
                                .get(test.path("field").asString());
                assertThat(value.state())
                        .as(test.path("id").asString())
                        .isEqualTo(test.path("state").asString());
                assertThat(value.normalized())
                        .as(test.path("id").asString())
                        .isEqualTo(
                                test.path("normalized").isNull()
                                        ? null
                                        : test.path("normalized").asString());
                if (!test.path("issue").isNull())
                    assertThat(value.issues())
                            .extracting(issue -> issue.code())
                            .contains(test.path("issue").asString());
                if (value.state().equals("PROPOSED")) {
                    assertThat(value.raw()).isNotBlank();
                    assertThat(value.evidence()).isNotEmpty();
                    assertThat(value.alternatives()).isNotEmpty();
                } else assertThat(value.evidence()).isEmpty();
            }
        }
    }

    @Test
    void contactOrderPermutationsKeepEachFieldsEvidenceAndConflicts() throws Exception {
        var inputs =
                List.of(
                        "Email: alex@example.test | Phone: +44 20 1234 5678",
                        "Phone: +44 20 1234 5678 | Email: alex@example.test",
                        "Contact\nMobile\nEmail\nalex@example.test\n+44 20 1234 5678");
        for (String raw : inputs) {
            var value = parse(raw);
            assertThat(value.personalInfo().get("email").normalized())
                    .isEqualTo("alex@example.test");
            assertThat(value.personalInfo().get("phone").normalized()).isEqualTo("+442012345678");
            assertThat(value.personalInfo().get("phone").evidence())
                    .allSatisfy(span -> assertThat(span.text()).doesNotContain("@"));
        }
        var repeated =
                parse("Email: alex@example.test\nEmail: alex@example.test")
                        .personalInfo()
                        .get("email");
        assertThat(repeated.evidence()).hasSize(2);
        assertThat(repeated.alternatives()).hasSize(1);
        assertThat(repeated.alternatives().getFirst().evidence()).hasSize(2);
    }

    @Test
    void inlineFieldsHaveSeparateOriginalSpans() throws Exception {
        var result =
                parse(
                        "Full name: Alex Example | E-mail: Alex@EXAMPLE.TEST | Mobile: (+84) 912 345 678");
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Alex Example");
        assertThat(result.personalInfo().get("email").normalized()).isEqualTo("Alex@example.test");
        assertThat(result.personalInfo().get("phone").normalized()).isEqualTo("+84912345678");
        assertThat(result.personalInfo().get("fullName").evidence().getFirst().text())
                .isEqualTo("Alex Example");
    }

    @Test
    void detachedLabelsAllowBlankLinesAndSeparateColon() throws Exception {
        var result = parse("Gender\n\n:\n\nNữ\nTelephone:\n\n(+84) 912 345 678");
        assertThat(result.personalInfo().get("gender").normalized()).isEqualTo("FEMALE");
        assertThat(result.personalInfo().get("phone").normalized()).isEqualTo("+84912345678");
    }

    @Test
    void summaryPreservesParagraphsAndStopsBeforeContact() throws Exception {
        var result =
                parse(
                        "Professional Summary\nFirst  line.\n\n• Second line.\nEmail: alex@example.test\nSkills\nSQL");
        var summary = result.personalInfo().get("summary");
        assertThat(summary.state()).isEqualTo("PROPOSED");
        assertThat(summary.raw()).isEqualTo("First  line.\n\n• Second line.");
        assertThat(summary.normalized()).isEqualTo(summary.raw());
        assertThat(result.personalInfo().get("email").normalized()).isEqualTo("alex@example.test");
    }

    @Test
    void inlineSummaryAndPersonalProfileHaveDifferentSemantics() throws Exception {
        var result =
                parse(
                        "Personal Profile\nDOB: 01/01/1990\nGender: Male\nCareer Objective: First sentence.\nSecond sentence.\nCertificates\nExample course");
        assertThat(result.personalInfo().get("summary").normalized())
                .isEqualTo("First sentence.\nSecond sentence.");
        assertThat(result.personalInfo().get("gender").normalized()).isEqualTo("MALE");
    }

    @Test
    void nicknameAndSuffixHonorificDoNotChooseALegalNameOrGender() throws Exception {
        var result = parse("Full name: ALEX (ALEXANDER EXAMPLE) - MR.");
        assertThat(result.personalInfo().get("fullName").normalized())
                .isEqualTo("ALEX (ALEXANDER EXAMPLE)");
        assertThat(result.personalInfo().get("fullName").evidence().getFirst().text())
                .endsWith("- MR.");
        assertThat(result.personalInfo().get("gender").state()).isEqualTo("NOT_FOUND");
    }

    @Test
    void headerRegionUsesMeaningfulContextAfterCoverLetterRatherThanFiveLines() throws Exception {
        var result =
                map(
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "Dear Hiring Team\nI am applying for the advertised position."),
                                new Page(
                                        2,
                                        "ok",
                                        "PERSONAL PROFILE\n\n\n\n\n\nSenior Analyst\n\nAlex Example\nContact\nalex@example.test")));
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Alex Example");
        assertThat(result.personalInfo().get("fullName").evidence().getFirst().page()).isEqualTo(2);
    }

    @Test
    void explicitNameBlockJoinsFragmentsWithOriginalEvidence() throws Exception {
        var field =
                parse("Name:\n\nAlex\nExample\nEmail: alex@example.test")
                        .personalInfo()
                        .get("fullName");
        assertThat(field.normalized()).isEqualTo("Alex Example");
        assertThat(field.raw()).isEqualTo("Alex\nExample");
        assertThat(field.evidence()).hasSize(2);
    }

    @Test
    void addressContinuesUntilFieldBoundaryAndRelocationIsNotResidence() throws Exception {
        var result =
                parse(
                        "Address: 12 Example Road\nExample District.\nWebsite: https://example.test\nOpen to relocate: Other City");
        assertThat(result.personalInfo().get("location").raw())
                .isEqualTo("12 Example Road\nExample District.");
        assertThat(result.personalInfo().get("location").normalized())
                .isEqualTo("12 Example Road Example District.");
    }

    @Test
    void trunkNotationKeepsRawAndAnIssueInsteadOfCreatingANumber() throws Exception {
        var field = parse("Phone: +84 (0) 912 345 678").personalInfo().get("phone");
        assertThat(field.state()).isEqualTo("INVALID");
        assertThat(field.normalized()).isNull();
        assertThat(field.alternatives().getFirst().raw()).isEqualTo("+84 (0) 912 345 678");
        assertThat(field.issues())
                .extracting(issue -> issue.code())
                .contains("PHONE_TRUNK_NOTATION_AMBIGUOUS");
    }

    @Test
    void pendingLabelGroupsUseTypeEvidenceRatherThanValueOrder() throws Exception {
        var result = parse("Contact\nEmail\nMobile\n+44 20 1234 5678\nalex@example.test");
        assertThat(result.personalInfo().get("email").normalized()).isEqualTo("alex@example.test");
        assertThat(result.personalInfo().get("phone").normalized()).isEqualTo("+442012345678");
        var ambiguous = parse("Personal Details\nName\nAddress\nAlex Example\nOther Example");
        assertThat(ambiguous.personalInfo().get("fullName").state()).isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.personalInfo().get("fullName").raw()).isNull();
    }

    @Test
    void whitespaceOnlyAffectsUnprovenSpansAndOwnershipRemainsProtected() throws Exception {
        var result =
                parse(
                        "Unrelated\tsidebar\nEmail: alex@example.test\nProfessional References\nContact\nEmail: referee@example.test\nPersonal Profile\nGender: F");
        assertThat(result.personalInfo().get("email").normalized()).isEqualTo("alex@example.test");
        assertThat(result.personalInfo().get("email").alternatives()).hasSize(1);
        assertThat(result.personalInfo().get("gender").normalized()).isEqualTo("FEMALE");
    }

    @Test
    void summaryContinuationRequiresExplicitMarkerAndConsecutiveOkPages() throws Exception {
        var linked =
                map(
                        List.of(
                                new Page(1, "ok", "Summary\nFirst paragraph."),
                                new Page(
                                        2,
                                        "ok",
                                        "Summary (continued)\nSecond paragraph.\nEducation")));
        assertThat(linked.personalInfo().get("summary").normalized())
                .isEqualTo("First paragraph.\nSecond paragraph.");
        var unlinked =
                map(
                        List.of(
                                new Page(1, "ok", "Summary\nFirst paragraph."),
                                new Page(2, "ok", "Possible continuation.")));
        assertThat(unlinked.personalInfo().get("summary").state()).isEqualTo("AMBIGUOUS");
        var gap =
                map(
                        List.of(
                                new Page(1, "ok", "Summary\nFirst paragraph."),
                                new Page(2, "failed", ""),
                                new Page(3, "ok", "Summary (continued)\nSecond paragraph.")));
        assertThat(gap.personalInfo().get("summary").state()).isEqualTo("AMBIGUOUS");
    }

    @Test
    void glyphSpacedHeadingAndUnicodeEvidenceKeepRawValueWhitespace() throws Exception {
        var result =
                parse(
                        "😀\r\nP E R S O N A L  I N F O R M A T I O N\r\nFull name: Nguyễn  Văn An\r\nGender: Nam");
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Nguyễn Văn An");
        assertThat(result.personalInfo().get("fullName").raw()).contains("  ");
        assertThat(result.personalInfo().get("gender").state()).isEqualTo("PROPOSED");
    }

    @Test
    void vietnameseNameEndingInAnhIsNotAnHonorificSuffixWithoutASeparator() throws Exception {
        var field = parse("Full name: Nguyễn Văn Anh").personalInfo().get("fullName");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.raw()).isEqualTo("Nguyễn Văn Anh");
        assertThat(field.normalized()).isEqualTo("Nguyễn Văn Anh");
        assertThat(field.evidence().getFirst().text()).isEqualTo("Nguyễn Văn Anh");
    }

    @Test
    void summaryExcludesPageNoiseBetweenContentSpans() throws Exception {
        var field =
                parse(
                                "Summary\nFirst synthetic sentence.\nPage 1\nSecond synthetic sentence.\nEducation")
                        .personalInfo()
                        .get("summary");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.raw()).isEqualTo("First synthetic sentence.\nSecond synthetic sentence.");
        assertThat(field.normalized()).isEqualTo(field.raw());
        assertThat(field.evidence()).hasSize(2);
        assertThat(field.evidence())
                .allSatisfy(span -> assertThat(span.text()).doesNotContain("Page 1"));
    }

    @Test
    void oversizedSummaryRetainsInvalidAlternativeRatherThanTruncatingIt() throws Exception {
        String content = "A".repeat(10001);
        var field = parse("Summary\n" + content + "\nEducation").personalInfo().get("summary");
        assertThat(field.state()).isEqualTo("INVALID");
        assertThat(field.raw()).isNull();
        assertThat(field.normalized()).isNull();
        assertThat(field.alternatives()).hasSize(1);
        assertThat(field.alternatives().getFirst().raw()).isEqualTo(content);
        assertThat(field.issues())
                .extracting(issue -> issue.code())
                .contains("VALUE_LENGTH_INVALID");
        assertThat(field.alternatives().getFirst().evidence().getFirst().text()).isEqualTo(content);
    }

    @Test
    void candidateBoundRejectsOversizedRepeatedContactInputWithoutSilentlyDroppingEvidence() {
        String content =
                String.join("\n", java.util.Collections.nCopies(129, "Email: alex@example.test"));
        assertThatThrownBy(() -> parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Personal candidate limit exceeded");
    }

    @Test
    void exceededHeaderContextDoesNotSuppressANewExplicitPersonalScope() throws Exception {
        var field =
                parse(
                                "Personal Profile\n"
                                        + "An ordinary narrative sentence.\n".repeat(33)
                                        + "Alex Before\nEmail: before@example.test\nEducation\nExample School\n"
                                        + "Personal Profile\nAlex After\nEmail: after@example.test")
                        .personalInfo()
                        .get("fullName");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.normalized()).isEqualTo("Alex After");
        assertThat(field.alternatives()).hasSize(1);
        assertThat(field.evidence())
                .singleElement()
                .satisfies(span -> assertThat(span.text()).isEqualTo("Alex After"));
    }

    private PersonalInfoMappingResult parse(String raw) throws Exception {
        return map(List.of(new Page(1, "ok", raw)));
    }

    private PersonalInfoMappingResult map(List<Page> pages) throws Exception {
        var config =
                (PersonalInfoDefinition)
                        MappingVersions.config(
                                Files.readString(
                                        Path.of(
                                                "src/main/resources/mapping/personal-info-config-v3.1.json")),
                                JsonMapper.builder().build());
        var input = new Extraction(1, 2, "cv-compass.ocr/2.0", "success", false, pages);
        var result =
                new PersonalInfoEngineV31()
                        .map(
                                new MappingClaim(
                                        3,
                                        1,
                                        4,
                                        config.revision(),
                                        PersonalInfoEngineV31.VERSION,
                                        1,
                                        UUID.randomUUID(),
                                        Instant.now().plusSeconds(60),
                                        config),
                                input);
        for (var field : result.personalInfo().values()) {
            field.evidence().forEach(span -> new MappingEngine().validateEvidence(input, span));
            field.alternatives()
                    .forEach(
                            value ->
                                    value.evidence()
                                            .forEach(
                                                    span ->
                                                            new MappingEngine()
                                                                    .validateEvidence(
                                                                            input, span)));
        }
        return result;
    }
}
