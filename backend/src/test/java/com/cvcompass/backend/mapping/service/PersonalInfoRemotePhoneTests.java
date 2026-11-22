package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.candidate.mapping.PersonalInfoMapper;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class PersonalInfoRemotePhoneTests {
    @Test
    void normalizesCountryPrefixesWithoutConvertingThemToDomesticNumbers() {
        for (String raw :
                List.of(
                        "(+84) 912 345 678",
                        "+84 (0) 912 345 678",
                        "(84) 912 345 678",
                        "0084 912 345 678",
                        "+84 912 345 678",
                        "(+84) (0)912345678")) {
            assertPhone(raw, "+84912345678");
        }
        assertPhone("(+44) 20 7946 0123", "+442079460123");
        assertPhone("+44 (0)20 7946 0123", "+442079460123");
    }

    @Test
    void removesOneUnicodeCaseInsensitiveLeadingLabelAndNormalizesWhitespace() {
        for (String label :
                List.of("Tel:", "PHONE.", "mobile", "đt:", "SĐT:", "ĐIỆN THOẠI:", "Hotline:")) {
            assertPhone(label + " 0912.345.678", "0912345678");
        }
        assertPhone("SĐT: 0912-345-678", "0912345678");
        assertPhone(
                Normalizer.normalize("  Điện thoại:\t0912  345 678\n", Normalizer.Form.NFD),
                "0912345678");
        assertPhone("Tel:\u00a00912\u00a0345\u00a0678", "0912345678");
    }

    @Test
    void retainsFormattingWithinASingleNumberAndExtensions() {
        Map<String, String> examples =
                Map.of(
                        "028 3823 4567", "02838234567",
                        "0912/345/678", "0912345678",
                        "0912;345678", "0912345678",
                        "0912345,678", "0912345678",
                        "0912345/678", "0912345678",
                        "0912 - 345 - 678", "0912345678",
                        "0912 – 345 – 678", "0912345678",
                        "1234567", "1234567",
                        "+123456789012345", "+123456789012345");
        examples.forEach(this::assertPhone);
        for (String extension : List.of("ext", "EXT.", "x", "máy lẻ", "MÁY LẺ")) {
            assertPhone("028 3823 4567 " + extension + " 123", "02838234567x123");
        }
        assertPhone("(+84) 912 345 678 ext. 12345678", "+84912345678x12345678");
    }

    @Test
    void keepsFirstOfMultipleNumbersWithAnInformationalFieldIssueAndVerbatimRaw() {
        for (String raw :
                List.of(
                        "0912 345 678 / 0987 654 321",
                        "0912345678; 0987654321",
                        "0912345678, 0987654321",
                        "0912345678 - 0987654321",
                        "0912345678 – 0987654321",
                        "0912345678/0987654321/0976543210",
                        "  Tel: 0912345678 / 0987654321  ")) {
            var result = verify(raw, false);
            var phone = result.personalInfo().get("phone");
            assertThat(phone.raw()).isEqualTo(raw);
            assertThat(phone.normalized()).isEqualTo("0912345678");
            assertThat(phone.issues())
                    .singleElement()
                    .satisfies(
                            issue -> {
                                assertThat(issue.code()).isEqualTo("PHONE_MULTIPLE_FIRST_KEPT");
                                assertThat(issue.field()).isEqualTo("phone");
                                assertThat(issue.evidence())
                                        .containsExactly(
                                                new Evidence(
                                                        11,
                                                        2,
                                                        0,
                                                        raw.codePointCount(0, raw.length()),
                                                        raw));
                            });
            String body = MappingDigests.canonical(result);
            var published =
                    new CompletedMappings(null, MappingRemoteFixtures.JSON)
                            .publishable(
                                    new CompletedMappings.Result(
                                            1, 3, result.quality(), body, 11, 13, 12));
            assertThat(published).isPresent();
            var patch =
                    PersonalInfoMapper.patch(
                            MappingRemoteFixtures.JSON.readTree(body),
                            MappingRemoteFixtures.JSON.createObjectNode(),
                            true);
            assertThat(patch.path("phone").asString()).isEqualTo(raw);
        }
    }

    @Test
    void rejectsDatesYearRangesAndInvalidNumbersIncludingDateCandidates() {
        for (String raw :
                List.of(
                        "2019 - 2021",
                        "2019 – 2021",
                        "2019—2021",
                        "12/05/2020",
                        "2020-05-12",
                        "abc123",
                        "12345",
                        "1234567890123456",
                        "+1234567890123456",
                        "Tel: 12/05/2020",
                        "Phone: 2019 - 2021",
                        "12/05/2020 ext 123",
                        "0912345678 / 2019 - 2021 x123",
                        "12/05/2020 / 0912345678",
                        "0912345678 / 12/05/2020",
                        "0912345678; 2019 - 2021",
                        "0912345678 ext 123456789",
                        "0912345678 ext",
                        "Phone: Tel: 0912345678",
                        "0912@345678")) {
            assertThat(PersonalInfoValidation.checkRemote("phone", raw))
                    .as(raw)
                    .isEqualTo(new PersonalInfoValidation.Checked(null, "INVALID_PHONE"));
        }
    }

    @Test
    void retainsLengthLimitBeforeRemovingLabelsAndSplittingNumbers() {
        for (String raw :
                List.of(
                        "",
                        " \n\t",
                        "1".repeat(51),
                        "Tel: " + "1".repeat(46),
                        "0912345678/" + "1".repeat(40))) {
            assertThat(PersonalInfoValidation.checkRemote("phone", raw))
                    .isEqualTo(new PersonalInfoValidation.Checked(null, "VALUE_LENGTH_INVALID"));
        }
        assertPhone("0912345678" + ".".repeat(40), "0912345678");
    }

    @Test
    void workerCannotSupplyTheServerOwnedMultiplePhoneIssue() {
        assertThatThrownBy(() -> verify("0912345678", true)).hasMessage("INVALID_RESULT");
    }

    @Test
    void sharedEngineValidationStillRejectsParenthesizedPlusPrefix() {
        assertThat(PersonalInfoValidation.check("phone", "(+84) 912 345 678"))
                .isEqualTo(new PersonalInfoValidation.Checked(null, "INVALID_PHONE"));
    }

    private void assertPhone(String raw, String normalized) {
        assertThat(PersonalInfoValidation.checkRemote("phone", raw))
                .as(raw)
                .isEqualTo(new PersonalInfoValidation.Checked(normalized, null));
    }

    private PersonalInfoMappingResult verify(String raw, boolean workerIssue) {
        var input =
                new Extraction(
                        11,
                        12,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(
                                new Page(1, "ok", MappingRemoteFixtures.RAW),
                                new Page(2, "ok", raw)),
                        "synthetic-profile",
                        List.of());
        String hash = MappingDigests.sha256(MappingDigests.canonical(input));
        var body = MappingRemoteFixtures.result(hash);
        ((ObjectNode) body.path("personal"))
                .putObject("phone")
                .put("value", raw)
                .putObject("evidence")
                .put("page", 2)
                .put("start", 0)
                .put("end", raw.codePointCount(0, raw.length()))
                .put("text", raw);
        if (workerIssue)
            body.withArray("issues")
                    .addObject()
                    .put("code", "PHONE_MULTIPLE_FIRST_KEPT")
                    .put("field", "phone");
        return new MappingRemoteVerifier()
                .verify(
                        MappingRemoteFixtures.claim(),
                        input,
                        hash,
                        body,
                        MappingRemoteFixtures.provenance());
    }
}
