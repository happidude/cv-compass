package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.PersonalField;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PersonalInfoGrammarV32Tests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void locationAliasesStopAtBoundariesAndKeepSameLineValues() throws Exception {
        assertLocation(
                "TRẦN VĂN BÌNH\n• Mobile: (+84) 900.000.001\n• Email: binh.tran@example.com\n"
                        + "• Location: Quận Bình Thạnh, TP.HCM\n• Education: Đại học Mẫu",
                "Quận Bình Thạnh, TP.HCM");
        assertLocation("Nơi ở: Phường Mẫu\nEducation\nĐại học Mẫu", "Phường Mẫu");
        assertLocation("Address:\nHCMC\nNotice period/Interview Availability: 30 days", "HCMC");
        assertLocation("Address:\nHCMC\nInterview Availability: 30 days", "HCMC");
        assertLocation("Address:\nHCMC\nQuận Mẫu", "HCMC\nQuận Mẫu");
        assertThat(field("Address:\nInterview Availability: 30 days", "location").state())
                .isEqualTo("NOT_FOUND");
        for (String bullet : List.of("•", "●", "▪", "*", "-", "–")) {
            assertLocation(
                    "Address: Phường Mẫu, TP.HCM (Q7)\n" + bullet + " Tốt nghiệp cử nhân luật",
                    "Phường Mẫu, TP.HCM (Q7)");
        }
    }

    @Test
    void trunkNotationPreservesRawAndUsesCountrySpecificNormalization() throws Exception {
        for (String raw :
                List.of("+84 (0)900 000 002", "+84(0)900000002", "(+84) (0) 900 000 002")) {
            assertValue(
                    field("Mobile: " + raw, "phone"),
                    raw,
                    "0900000002",
                    "PHONE_TRUNK_NOTATION_NORMALIZED");
        }
        assertValue(
                field("Phone: +44 (0)20 7946 0000", "phone"),
                "+44 (0)20 7946 0000",
                "+4402079460000",
                "PHONE_TRUNK_NOTATION_KEPT");
        assertValue(
                field("Contact\nEmail\nMobile\n+84 (0)900 000 002\nmau@example.com", "phone"),
                "+84 (0)900 000 002",
                "0900000002",
                "PHONE_TRUNK_NOTATION_NORMALIZED");
        assertThat(field("Mobile: (+84) 900.000.001", "phone").normalized())
                .isEqualTo("+84900000001");
    }

    @Test
    void summaryJoinsSectionsInReadingOrderWithSeparateOriginalEvidence() throws Exception {
        var summary =
                field(
                        "Tóm tắt chuyên môn\nĐoạn  A.\n\nDòng tiếp.\n"
                                + "Định hướng nghề nghiệp: Đoạn B.\n"
                                + "Kinh nghiệm làm việc\nNội dung khác.",
                        "summary");
        assertValue(
                summary,
                "Đoạn  A.\n\nDòng tiếp.\n\nĐoạn B.",
                "Đoạn  A.\n\nDòng tiếp.\n\nĐoạn B.",
                "SUMMARY_MULTI_SECTION");
        assertThat(summary.evidence())
                .extracting(span -> span.text())
                .containsExactly("Đoạn  A.", "Dòng tiếp.", "Đoạn B.");

        var acrossPages =
                field(
                        "summary",
                        new Page(1, "ok", "Summary\nĐoạn A."),
                        new Page(2, "ok", "Career Objective\nĐoạn B.\nEducation"));
        assertValue(
                acrossPages, "Đoạn A.\n\nĐoạn B.", "Đoạn A.\n\nĐoạn B.", "SUMMARY_MULTI_SECTION");
        assertThat(acrossPages.evidence()).extracting(span -> span.page()).containsExactly(1, 2);
        assertThat(
                        field(
                                        "CURRICULUM VITAE\nCAREER OBJECTIVE:\nĐoạn mục tiêu.\n"
                                                + "ABILITIES AND QUALIFICATIONS:\nKỹ năng X",
                                        "summary")
                                .raw())
                .isEqualTo("Đoạn mục tiêu.");
    }

    @Test
    void unlabeledFallbackProposesOnlyAUniqueValueOnPageOneOutsideProtectedRegions()
            throws Exception {
        String raw =
                "PROFESSIONAL SUMMARY\nSynthetic statement.\nEDUCATION\nLê Thị Mẫu\n"
                        + "\uf095 0900000003\n\uf199 mau.le@example.com\n"
                        + "References\nEmail: reference@example.com\nPhone: 0900000004\n"
                        + "Recruiter: Synthetic Contact\n0900000005\nrecruiter@example.com\n"
                        + "Company Contact\n0900000006\ncompany@example.com";
        assertValue(
                field(raw, "email"),
                "mau.le@example.com",
                "mau.le@example.com",
                "UNLABELED_SINGLE_VALUE");
        assertValue(field(raw, "phone"), "0900000003", "0900000003", "UNLABELED_SINGLE_VALUE");

        var repeated =
                field(
                        "email",
                        new Page(1, "ok", "Education\nmau@example.com"),
                        new Page(2, "ok", "mau@EXAMPLE.COM"));
        assertValue(repeated, "mau@example.com", "mau@example.com", "UNLABELED_SINGLE_VALUE");
        assertThat(repeated.evidence()).extracting(span -> span.page()).containsExactly(1, 2);

        assertThat(
                        field(
                                        "email",
                                        new Page(1, "ok", "Education"),
                                        new Page(2, "ok", "mau@example.com"))
                                .state())
                .isEqualTo("NOT_FOUND");
        assertThat(
                        field(
                                        "Education\n01-02-2000\nCandidate ID: 123456789\nCV-123456789",
                                        "phone")
                                .state())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void unlabeledConflictsAcrossPagesAbstainAndStrongerSourcesTakePrecedence() throws Exception {
        var ambiguous =
                field(
                        "email",
                        new Page(1, "ok", "Education\nfirst@example.com"),
                        new Page(2, "ok", "second@example.com"));
        assertThat(ambiguous.state()).isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.alternatives())
                .extracting(value -> value.normalized())
                .containsExactly("first@example.com", "second@example.com");
        assertThat(ambiguous.issues())
                .extracting(issue -> issue.code())
                .contains("CONFLICTING_VALUES")
                .doesNotContain("UNLABELED_SINGLE_VALUE");

        var phones = field("Education\n0900000003 mau@example.com 0900000004", "phone");
        assertThat(phones.state()).isEqualTo("AMBIGUOUS");
        assertThat(phones.alternatives())
                .extracting(value -> value.raw())
                .containsExactly("0900000003", "0900000004");

        var labeled = field("Email: chosen@example.com\nEducation\nother@example.com", "email");
        assertThat(labeled.normalized()).isEqualTo("chosen@example.com");
        assertThat(labeled.issues()).isEmpty();
        var contextual = field("Contact\n0900000003\nEducation\n0900000004", "phone");
        assertThat(contextual.normalized()).isEqualTo("0900000003");
        assertThat(contextual.issues()).isEmpty();
    }

    @Test
    void detachedLocationKeepsAlternativesWhenOtherLabelsPrecedeValues() throws Exception {
        String raw =
                "PERSONAL INFORMATION\nAddress:\nPhone:\nEmail:\nQuận Mẫu, TP.HCM\n"
                        + "0900 000 004\nmau@example.com\nEDUCATION\nX";
        var v31 = definition("3.1");
        var legacy =
                new PersonalInfoEngineV31()
                        .map(claim(v31), extraction(new Page(1, "ok", raw)))
                        .personalInfo()
                        .get("location");
        var current = field(raw, "location");
        assertThat(legacy.state()).isEqualTo("AMBIGUOUS");
        assertThat(legacy.alternatives()).isNotEmpty();
        assertThat(current.state()).isEqualTo(legacy.state());
        assertThat(current.alternatives()).isEqualTo(legacy.alternatives());
    }

    @Test
    void unlabeledPhoneIgnoresYearSequencesOutsidePageOne() throws Exception {
        for (String years : List.of("2019 2020 - 2023", "1999 – 2000 / 2023")) {
            var phone =
                    field(
                            "phone",
                            new Page(1, "ok", "Education\n\uf095 0900000003"),
                            new Page(2, "ok", years));
            assertValue(phone, "0900000003", "0900000003", "UNLABELED_SINGLE_VALUE");
            assertThat(phone.alternatives()).hasSize(1);
        }
        assertThat(field("Phone: 2019 2020 - 2023", "phone").normalized())
                .isEqualTo("201920202023");
    }

    @Test
    void legacyReplayMatchesBaselineBytesForTheSameSyntheticInput() throws Exception {
        var input =
                extraction(
                        new Page(
                                1,
                                "ok",
                                "Full name: Tran Van Mau\nAddress: HCMC\n• Graduated from sample school\n"
                                        + "Phone: +84 (0)900 000 002\nSummary\nĐoạn A.\n"
                                        + "Career Objective\nĐoạn B.\nEducation\nmau@example.com"));
        var v30 = definition("3.0");
        var v31 = definition("3.1");
        var results =
                List.of(
                        new PersonalInfoEngine(new ExperienceEngine(new MappingEngine()))
                                .map(claim(v30), input),
                        new PersonalInfoEngineV31().map(claim(v31), input));
        var digests = new ArrayList<String>();
        for (var result : results) {
            digests.add(MappingDigests.sha256(JSON.writeValueAsString(result)));
        }
        assertThat(digests)
                .containsExactly(
                        "bcfea527ac50d3434dba90045a50e520eae6555e103c0adfb0bdd23fddb885e4",
                        "aeb8cb918353f0423ec1b7e3af974efa9e61fe076a182c05db6c12e9e5a3294b");
    }

    private static void assertLocation(String input, String raw) throws Exception {
        var location = field(input, "location");
        assertThat(location.state()).isEqualTo("PROPOSED");
        assertThat(location.raw()).isEqualTo(raw);
    }

    private static void assertValue(
            PersonalField field, String raw, String normalized, String issue) {
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.raw()).isEqualTo(raw);
        assertThat(field.normalized()).isEqualTo(normalized);
        assertThat(field.issues()).extracting(value -> value.code()).contains(issue);
    }

    private static PersonalField field(String raw, String name) throws Exception {
        return field(name, new Page(1, "ok", raw));
    }

    private static PersonalField field(String name, Page... pages) throws Exception {
        var input = extraction(pages);
        var definition = definition("3.2");
        var result =
                new PersonalInfoEngineV32().map(claim(definition), input).personalInfo().get(name);
        result.evidence().forEach(span -> new MappingEngine().validateEvidence(input, span));
        return result;
    }

    private static Extraction extraction(Page... pages) {
        return new Extraction(1, 2, "cv-compass.ocr/2.0", "success", false, List.of(pages));
    }

    private static PersonalInfoDefinition definition(String version) throws Exception {
        return (PersonalInfoDefinition) MappingVersions.config(configBody(version), JSON);
    }

    private static String configBody(String version) throws Exception {
        String suffix = version.equals("3.0") ? "-v3" : "-v" + version;
        try (var stream =
                PersonalInfoGrammarV32Tests.class.getResourceAsStream(
                        "/mapping/personal-info-config" + suffix + ".json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MappingClaim claim(PersonalInfoDefinition definition) {
        return new MappingClaim(
                3,
                1,
                4,
                definition.revision(),
                definition.engineCompatibility(),
                1,
                new UUID(0, 1),
                Instant.EPOCH,
                definition);
    }
}
