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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** All inputs are synthetic; layouts mirror patterns seen in real CVs without their content. */
class PersonalInfoGrammarV33Tests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void labelledDateOfBirthKeepsStatedPrecision() throws Exception {
        var cases =
                Map.of(
                        "DOB : 09/ 02/ 1990", "1990-02-09",
                        " Birthday: October 7th,1991", "1991-10-07",
                        "Ngày sinh: 25/12/1992", "1992-12-25",
                        "Date of birth: 1993", "1993",
                        "Năm sinh: 05/1994", "1994-05",
                        "Ngày sinh: ngày 3 tháng 4 năm 1995", "1995-04-03",
                        "Date of birth: 1996-07-08", "1996-07-08",
                        "Date of birth: 12 Mar 1997", "1997-03-12");
        for (var entry : cases.entrySet()) {
            var field = field(entry.getKey(), "dateOfBirth");
            assertThat(field.state()).as(entry.getKey()).isEqualTo("PROPOSED");
            assertThat(field.normalized()).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        assertThat(field("DOB : 09/ 02/ 1990", "dateOfBirth").issues())
                .extracting(issue -> issue.code())
                .contains("DATE_OF_BIRTH_DAY_FIRST_ASSUMED");
        assertThat(field("Date of birth: 10/21/1990", "dateOfBirth").issues())
                .extracting(issue -> issue.code())
                .contains("DATE_OF_BIRTH_MONTH_FIRST");
        var invalid = field("Date of birth: 31/02/1990", "dateOfBirth");
        assertThat(invalid.state()).isEqualTo("INVALID");
        assertThat(invalid.issues())
                .extracting(issue -> issue.code())
                .contains("INVALID_DATE_OF_BIRTH");
    }

    @Test
    void dateOfBirthLabelEndsAtTheNextLabelOnTheSameLine() throws Exception {
        var field = field("DOB: 01/01/1990 Place of birth: Ha Noi", "dateOfBirth");
        assertThat(field.raw()).isEqualTo("01/01/1990");
        assertThat(field.normalized()).isEqualTo("1990-01-01");
    }

    @Test
    void unlabeledBirthDateNeedsOneStandaloneDateInTheProfileArea() throws Exception {
        var single =
                field(
                        "TRAN VAN MAU\n15/03/1988\n0900000003\nWork Experience\n"
                                + "09/2010 - 07/2014 Mau Company",
                        "dateOfBirth");
        assertThat(single.state()).isEqualTo("PROPOSED");
        assertThat(single.normalized()).isEqualTo("1988-03-15");
        assertThat(single.issues())
                .extracting(issue -> issue.code())
                .contains("UNLABELED_SINGLE_VALUE");
        var two = field("TRAN VAN MAU\n15/03/1988\n16/04/1989\nWork Experience", "dateOfBirth");
        assertThat(two.state()).isEqualTo("AMBIGUOUS");
        assertThat(field("Work Experience\n15/03/1988", "dateOfBirth").state())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void locationNormalizesToTheProvinceNamedLast() throws Exception {
        assertLocation(
                "Address: 12 Đường Mẫu, Quận 1, TP.HCM",
                "12 Đường Mẫu, Quận 1, TP.HCM",
                "Hồ Chí Minh");
        assertLocation("Location: Hanoi", "Hanoi", "Hà Nội");
        assertLocation("Địa chỉ: 1 Xa lộ Hà Nội, Thủ Đức, HCMC", null, "Hồ Chí Minh");
        assertLocation("Address: Phường Mẫu, Thành phố Đà Nẵng", null, "Đà Nẵng");
        assertLocation("Nơi ở: Xã Mẫu, Bình Dương", null, "Bình Dương");
        assertLocation("Address: Sample Ward, Ho Chi Minh City, Vietnam", null, "Hồ Chí Minh");
        var foreign = field("Address: 10 Sample Road, Singapore", "location");
        assertThat(foreign.state()).isEqualTo("PROPOSED");
        assertThat(foreign.normalized()).isEqualTo("10 Sample Road, Singapore");
        assertThat(foreign.issues())
                .extracting(issue -> issue.code())
                .contains("LOCATION_PROVINCE_UNRECOGNIZED");
    }

    @Test
    void unlabeledHeaderAddressNextToContactsIsProposedWithFlag() throws Exception {
        var location =
                field(
                        "Curriculum Vitae\nNGUYEN THI MAU\n12/3A Duong Mau Street, Phuong Mau\n"
                                + "Ward, District 7, HCMC\nTel. 090.0000 004\n"
                                + "Email: mau@example.com\nEDUCATION\nĐại học Mẫu",
                        "location");
        assertThat(location.state()).isEqualTo("PROPOSED");
        assertThat(location.raw())
                .isEqualTo("12/3A Duong Mau Street, Phuong Mau\nWard, District 7, HCMC");
        assertThat(location.normalized()).isEqualTo("Hồ Chí Minh");
        assertThat(location.evidence()).hasSize(2);
        assertThat(location.issues())
                .extracting(issue -> issue.code())
                .contains("UNLABELED_SINGLE_VALUE");
    }

    @Test
    void unlabeledAddressStopsAtTheProvinceWhenColumnsInterleave() throws Exception {
        var location =
                field(
                        "Mau Van Tran\nWork Experience\nmau@example.com\n"
                                + "Mau Office (March 2025 – Present)\n"
                                + "99/9 Đường Mẫu,Phường Mẫu, TP\nHồ Chí Minh Quality\n"
                                + "Sample duties:",
                        "location");
        assertThat(location.state()).isEqualTo("PROPOSED");
        assertThat(location.raw()).isEqualTo("99/9 Đường Mẫu,Phường Mẫu, TP\nHồ Chí Minh");
        assertThat(location.evidence().getLast().text()).isEqualTo("Hồ Chí Minh");
    }

    @Test
    void addressWithoutNearbyContactOrProvinceIsNotGuessed() throws Exception {
        assertThat(
                        field(
                                        "Tran Van Mau\nmau@example.com\nWork Experience\nMau Company\n"
                                                + "Line one\nLine two\nLine three\nLine four\nLine five\n"
                                                + "12 Sample Street, District 1, HCMC",
                                        "location")
                                .state())
                .isEqualTo("NOT_FOUND");
        assertThat(
                        field("Tran Van Mau\nmau@example.com\n12 Sample Street, Ward 3", "location")
                                .state())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void summaryBulletListContinuesOnTheNextPage() throws Exception {
        var summary =
                field(
                        "summary",
                        new Page(
                                1,
                                "ok",
                                "Full name: Tran Van Mau\nCAREER OBJECTIVES\n"
                                        + "- Muc tieu thu nhat cua ung vien.\n"
                                        + "- Muc tieu thu hai cua ung vien."),
                        new Page(
                                2,
                                "ok",
                                "- Muc tieu thu ba cua ung vien.\nWORKING EXPERIENCES\n"
                                        + "2020 – 2025 Mau Company"));
        assertThat(summary.state()).isEqualTo("PROPOSED");
        assertThat(summary.raw()).endsWith("- Muc tieu thu ba cua ung vien.");
        assertThat(summary.evidence()).extracting(span -> span.page()).contains(1, 2);
        assertThat(summary.issues())
                .extracting(issue -> issue.code())
                .contains("SUMMARY_CROSS_PAGE");
    }

    @Test
    void summaryDoesNotSwallowAnUnrelatedNextPage() throws Exception {
        var summary =
                field(
                        "summary",
                        new Page(1, "ok", "CAREER OBJECTIVES\n- Muc tieu thu nhat."),
                        new Page(2, "ok", "Mau Company 2020 – 2025\nWORKING EXPERIENCES"));
        assertThat(summary.state()).isEqualTo("AMBIGUOUS");
        assertThat(summary.issues())
                .extracting(issue -> issue.code())
                .contains("SUMMARY_CONTINUATION_UNVERIFIED");
    }

    @Test
    void untitledHeaderGetsNameAndProseSummaryButNotTheJobTitle() throws Exception {
        String text =
                "Trần Văn Mẫu\nSample Supervisor\n"
                        + "Seasoned sample professional who builds simple tools and helps\n"
                        + "teams work better, with a calm style, clear notes and steady habits\n"
                        + "that keep each sample project moving forward for the whole team\n"
                        + "and its partners.\n"
                        + "01/02/1987\n0900000005\nWork Experience\nmau@example.com";
        var name = field(text, "fullName");
        assertThat(name.state()).isEqualTo("PROPOSED");
        assertThat(name.normalized()).isEqualTo("Trần Văn Mẫu");
        var summary = field(text, "summary");
        assertThat(summary.state()).isEqualTo("PROPOSED");
        assertThat(summary.raw()).startsWith("Seasoned sample").endsWith("partners.");
        assertThat(summary.issues())
                .extracting(issue -> issue.code())
                .contains("UNLABELED_SINGLE_VALUE");
        assertThat(field(text, "dateOfBirth").normalized()).isEqualTo("1987-02-01");
    }

    @Test
    void olderGrammarsKeepTheirFieldSetAndRawLocation() throws Exception {
        var input =
                extraction(new Page(1, "ok", "Address: 12 Sample Street, HCMC\nDOB: 01/01/1990"));
        var v32 = definition("3.2");
        var result = new PersonalInfoEngineV32().map(claim(v32), input).personalInfo();
        assertThat(result).doesNotContainKey("dateOfBirth");
        assertThat(result.get("location").normalized()).isEqualTo("12 Sample Street, HCMC");
        var v33 = definition("3.3");
        assertThat(new PersonalInfoEngineV33().map(claim(v33), input).personalInfo().keySet())
                .containsExactlyInAnyOrderElementsOf(PersonalInfoDefinition.V33_FIELD_NAMES);
        assertThat(v33.fieldNames()).contains("dateOfBirth");
        assertThat(List.copyOf(PersonalInfoDefinition.FIELD_NAMES)).doesNotContain("dateOfBirth");
    }

    private static void assertLocation(String input, String raw, String normalized)
            throws Exception {
        var location = field(input, "location");
        assertThat(location.state()).as(input).isEqualTo("PROPOSED");
        if (raw != null) assertThat(location.raw()).isEqualTo(raw);
        assertThat(location.normalized()).as(input).isEqualTo(normalized);
    }

    private static PersonalField field(String raw, String name) throws Exception {
        return field(name, new Page(1, "ok", raw));
    }

    private static PersonalField field(String name, Page... pages) throws Exception {
        var input = extraction(pages);
        var definition = definition("3.3");
        var result =
                new PersonalInfoEngineV33().map(claim(definition), input).personalInfo().get(name);
        result.evidence().forEach(span -> new MappingEngine().validateEvidence(input, span));
        return result;
    }

    private static Extraction extraction(Page... pages) {
        return new Extraction(1, 2, "cv-compass.ocr/2.0", "success", false, List.of(pages));
    }

    private static PersonalInfoDefinition definition(String version) throws Exception {
        try (var stream =
                PersonalInfoGrammarV33Tests.class.getResourceAsStream(
                        "/mapping/personal-info-config-v" + version + ".json")) {
            return (PersonalInfoDefinition)
                    MappingVersions.config(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8), JSON);
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
