package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

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

class PersonalInfoEngineTests {
    private final PersonalInfoEngine engine =
            new PersonalInfoEngine(new ExperienceEngine(new MappingEngine()));

    @Test
    void summaryPageBoundaryAbstainsInsteadOfSelectingTruncatedParagraph() throws Exception {
        var result =
                map(
                        new Extraction(
                                1,
                                2,
                                "cv-compass.ocr/2.0",
                                "success",
                                false,
                                List.of(
                                        new Page(1, "ok", "Profile\nSynthetic first paragraph."),
                                        new Page(2, "ok", "Possible continuation."))),
                        config());
        assertThat(result.personalInfo().get("summary").state()).isEqualTo("AMBIGUOUS");
        assertThat(result.personalInfo().get("summary").raw()).isNull();
        assertThat(result.personalInfo().get("summary").alternatives()).hasSize(1);
    }

    @Test
    void syntheticFixtureCasesCoverBilingualOwnershipAndAbstention() throws Exception {
        try (var stream =
                getClass().getResourceAsStream("/mapping/personal-info/synthetic-cases.json")) {
            var cases = JsonMapper.builder().build().readTree(stream);
            for (var fixture : cases) {
                var field =
                        parse(fixture.path("raw").stringValue())
                                .personalInfo()
                                .get(fixture.path("field").stringValue());
                assertThat(field.state())
                        .as(fixture.path("id").stringValue())
                        .isEqualTo(fixture.path("state").stringValue());
                assertThat(field.normalized())
                        .isEqualTo(
                                fixture.path("normalized").isNull()
                                        ? null
                                        : fixture.path("normalized").stringValue());
            }
        }
    }

    @Test
    void genericContactAndPersonalHeadingsAreNeverUnlabelledNames() throws Exception {
        var base = config();
        var fields = new java.util.HashMap<>(base.personalFields());
        var original = fields.get("fullName");
        fields.put(
                "fullName",
                new PersonalInfoDefinition.FieldRule(
                        true, original.labels(), List.of("EXPLICIT_LABEL", "PROFILE_HEADER")));
        var definition =
                new PersonalInfoDefinition(
                        3,
                        base.engineCompatibility(),
                        base.revision(),
                        base.experience(),
                        fields,
                        base.sectionHeadings(),
                        base.excludedRegions());
        for (String heading :
                List.of(
                        "Contact Details",
                        "Contact Information",
                        "Personal Details",
                        "Candidate Information",
                        "Thông tin liên hệ")) {
            var field =
                    map(input(heading + "\nEmail: synthetic@example.test"), definition)
                            .personalInfo()
                            .get("fullName");
            assertThat(field.state()).as(heading).isEqualTo("NOT_FOUND");
            assertThat(field.raw()).isNull();
        }
        assertThat(
                        parse("References\nContact Details\nEmail: excluded@example.test")
                                .personalInfo()
                                .get("email")
                                .state())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void inlineSummaryLabelRetainsWholeParagraphUntilNextHeading() throws Exception {
        for (String label : List.of("Summary", "Tóm tắt", "Profile")) {
            String raw =
                    label
                            + ": First synthetic sentence.\nSecond sentence.\nEducation\nExample School";
            var field = parse(raw).personalInfo().get("summary");
            assertThat(field.raw())
                    .as(label)
                    .isEqualTo("First synthetic sentence.\nSecond sentence.");
            assertThat(field.state()).isEqualTo("PROPOSED");
            assertThat(field.evidence()).hasSize(2);
            field.evidence()
                    .forEach(
                            e ->
                                    assertThat(
                                                    raw.substring(
                                                            raw.offsetByCodePoints(0, e.start()),
                                                            raw.offsetByCodePoints(0, e.end())))
                                            .isEqualTo(e.text()));
        }
        assertThat(
                        parse("References\nSummary: Reference details.\nContinuation.")
                                .personalInfo()
                                .get("summary")
                                .state())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void unlabelledParenthesizedPhoneIsSupportedOnlyInContactAndTemporalGuardRemains()
            throws Exception {
        var field = parse("Contact\n(020) 1234 5678").personalInfo().get("phone");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.raw()).isEqualTo("(020) 1234 5678");
        assertThat(field.normalized()).isEqualTo("02012345678");
        assertThat(field.evidence().getFirst().text()).isEqualTo("(020) 1234 5678");
        assertThat(parse("(020) 1234 5678").personalInfo().get("phone").state())
                .isEqualTo("NOT_FOUND");
        assertThat(parse("Contact\n2024-2025").personalInfo().get("phone").normalized()).isNull();
        assertThat(parse("Contact\nDocument ID: 123456789").personalInfo().get("phone").state())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void labelledFieldsKeepRawAndExcludeReferenceContact() throws Exception {
        var result =
                parse(
                        "Full name: Ms. Ada Example\nEmail: Ada@example.test\nPhone: +44 (20) 1234-5678 ext 9\nAddress: Example City.\nGender: Female\nSummary\nSynthetic career paragraph.\nEducation\nExample School\nReferences\nEmail: other@example.test");
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Ada Example");
        assertThat(result.personalInfo().get("fullName").raw()).isEqualTo("Ada Example");
        assertThat(result.personalInfo().get("email").normalized()).isEqualTo("Ada@example.test");
        assertThat(result.personalInfo().get("phone").normalized()).isEqualTo("+442012345678x9");
        assertThat(result.personalInfo().get("location").normalized()).isEqualTo("Example City.");
        assertThat(result.personalInfo().get("gender").normalized()).isEqualTo("FEMALE");
        assertThat(result.personalInfo().get("summary").raw())
                .isEqualTo("Synthetic career paragraph.");
        assertThat(result.personalInfo().get("email").alternatives()).hasSize(1);
    }

    @Test
    void genderRequiresLabelsAndPreservesUnknownAndConflictingAlternatives() throws Exception {
        assertThat(
                        parse("Ms. Ada Example\nShe is a developer.")
                                .personalInfo()
                                .get("gender")
                                .state())
                .isEqualTo("NOT_FOUND");
        var unknown = parse("Giới tính: Nonbinary").personalInfo().get("gender");
        assertThat(unknown.state()).isEqualTo("INVALID");
        assertThat(unknown.alternatives().getFirst().raw()).isEqualTo("Nonbinary");
        assertThat(unknown.alternatives().getFirst().normalized()).isNull();
        assertThat(unknown.issues()).extracting(i -> i.code()).contains("UNRECOGNIZED_GENDER");
        var conflict = parse("Sex: M\nPhái: Nữ").personalInfo().get("gender");
        assertThat(conflict.state()).isEqualTo("AMBIGUOUS");
        assertThat(conflict.raw()).isNull();
        assertThat(conflict.alternatives()).hasSize(2);
    }

    @Test
    void repeatedValuesDeduplicateButDifferentValuesRemainAmbiguous() throws Exception {
        assertThat(
                        parse("Email: a@example.test\nEmail: a@example.test")
                                .personalInfo()
                                .get("email")
                                .evidence())
                .hasSize(2);
        var field =
                parse("Email: a@example.test\nEmail: b@example.test").personalInfo().get("email");
        assertThat(field.state()).isEqualTo("AMBIGUOUS");
        assertThat(field.normalized()).isNull();
        assertThat(field.alternatives()).hasSize(2);
    }

    @Test
    void invalidValuesAreNotRepairedAndCompanyEducationRegionsAreExcluded() throws Exception {
        var result =
                parse(
                        "Email: a@@example.test\nPhone: 2024-11-03\nExperience\nAddress: Company City\nEducation\nAddress: School City");
        assertThat(result.personalInfo().get("email").state()).isEqualTo("INVALID");
        assertThat(result.personalInfo().get("phone").state()).isEqualTo("INVALID");
        assertThat(result.personalInfo().get("location").state()).isEqualTo("NOT_FOUND");
    }

    @Test
    void evidenceUsesCodePointsAndRawRemainsUnchanged() throws Exception {
        String raw = "😀\nHọ tên: Ông Nguyễn Văn Mẫu\nGiới tính: Nam";
        var result = parse(raw);
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Nguyễn Văn Mẫu");
        for (var field : result.personalInfo().values())
            for (var span : field.evidence()) {
                assertThat(
                                raw.substring(
                                        raw.offsetByCodePoints(0, span.start()),
                                        raw.offsetByCodePoints(0, span.end())))
                        .isEqualTo(span.text());
            }
    }

    @Test
    void untrustedReadingOrderAbstainsAndDoesNotTreatTitleAsName() throws Exception {
        assertThat(
                        parse("Senior Software Engineer\nEmail: a@example.test")
                                .personalInfo()
                                .get("fullName")
                                .state())
                .isEqualTo("NOT_FOUND");
        var result =
                map(
                        new Extraction(
                                1,
                                2,
                                "cv-compass.ocr/2.0",
                                "success",
                                false,
                                List.of(
                                        new Page(
                                                1,
                                                "ok",
                                                "Email: a@example.test",
                                                List.of("MULTI_COLUMN")))),
                        config());
        assertThat(result.personalInfo().get("email").state()).isEqualTo("AMBIGUOUS");
    }

    @Test
    void disabledFieldsNeverProduceValuesAndExperienceMatchesV2() throws Exception {
        var base = config();
        var fields = new java.util.HashMap<>(base.personalFields());
        var old = fields.get("gender");
        fields.put(
                "gender",
                new PersonalInfoDefinition.FieldRule(false, old.labels(), old.priorities()));
        var config =
                new PersonalInfoDefinition(
                        3,
                        base.engineCompatibility(),
                        base.revision(),
                        base.experience(),
                        fields,
                        base.sectionHeadings(),
                        base.excludedRegions());
        var result =
                map(
                        new Extraction(
                                1,
                                2,
                                "cv-compass.ocr/2.0",
                                "success",
                                false,
                                List.of(new Page(1, "ok", "Gender: M"))),
                        config);
        assertThat(result.personalInfo().get("gender").enabled()).isFalse();
        assertThat(result.personalInfo().get("gender").issues())
                .extracting(i -> i.code())
                .contains("FIELD_DISABLED");
    }

    @Test
    void allApprovedGenderSpellingsRequireExplicitLabels() throws Exception {
        for (String label : List.of("Gender", "Sex", "Giới tính", "Phái")) {
            for (String value : List.of("Nam", "Male", "M"))
                assertThat(parse(label + ": " + value).personalInfo().get("gender").normalized())
                        .isEqualTo("MALE");
            for (String value : List.of("Nữ", "Female", "F"))
                assertThat(parse(label + ": " + value).personalInfo().get("gender").normalized())
                        .isEqualTo("FEMALE");
        }
    }

    @Test
    void configuredUnlabelledHeaderUsesContactContextButNeverEmailOrFileNameToInventName()
            throws Exception {
        var base = config();
        var fields = new java.util.HashMap<>(base.personalFields());
        fields.put(
                "fullName",
                new PersonalInfoDefinition.FieldRule(
                        true,
                        List.of("Full name", "Họ tên"),
                        List.of("EXPLICIT_LABEL", "PROFILE_HEADER")));
        var config =
                new PersonalInfoDefinition(
                        3,
                        base.engineCompatibility(),
                        base.revision(),
                        base.experience(),
                        fields,
                        base.sectionHeadings(),
                        base.excludedRegions());
        var result = map(input("Ada Example\nEmail: synthetic@example.test"), config);
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Ada Example");
        assertThat(
                        map(
                                        input(
                                                "Senior Software Engineer\nEmail: ada.example@example.test"),
                                        config)
                                .personalInfo()
                                .get("fullName")
                                .state())
                .isEqualTo("NOT_FOUND");
        assertThat(map(input("Ada Example"), config).personalInfo().get("fullName").state())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void inlineReferenceRecruiterCompanyLabelsExcludeFollowingContacts() throws Exception {
        for (String owner :
                List.of(
                        "Reference: Example Person",
                        "Recruiter: Example Person",
                        "Company: Example Workshop")) {
            var result =
                    parse(
                            "Email: owner@example.test\n"
                                    + owner
                                    + "\nEmail: excluded@example.test\nGender: M\nAddress: Excluded City");
            assertThat(result.personalInfo().get("email").normalized())
                    .isEqualTo("owner@example.test");
            assertThat(result.personalInfo().get("gender").state()).isEqualTo("NOT_FOUND");
            assertThat(result.personalInfo().get("location").state()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void unicodeDecomposedLabelsAndNamesKeepExactRawSpan() throws Exception {
        String raw =
                java.text.Normalizer.normalize(
                        "Họ tên: Chị Nguyễn Văn Mẫu\nGiới tính: Nữ", java.text.Normalizer.Form.NFD);
        var result = parse(raw);
        assertThat(result.personalInfo().get("fullName").normalized()).isEqualTo("Nguyễn Văn Mẫu");
        assertThat(result.personalInfo().get("gender").normalized()).isEqualTo("FEMALE");
        result.personalInfo()
                .values()
                .forEach(
                        field ->
                                field.evidence()
                                        .forEach(
                                                span ->
                                                        assertThat(
                                                                        raw.substring(
                                                                                raw
                                                                                        .offsetByCodePoints(
                                                                                                0,
                                                                                                span
                                                                                                        .start()),
                                                                                raw
                                                                                        .offsetByCodePoints(
                                                                                                0,
                                                                                                span
                                                                                                        .end())))
                                                                .isEqualTo(span.text())));
    }

    @Test
    void lengthLimitsAndOcrErrorsNeverTruncateOrRepair() throws Exception {
        var result =
                parse(
                        "Full name: "
                                + "A".repeat(256)
                                + " Example\nAddress: "
                                + "Z".repeat(256)
                                + "\nPhone: +84 O12 345 678\nEmail: wrong @example.test");
        for (String field : List.of("fullName", "location", "phone", "email")) {
            assertThat(result.personalInfo().get(field).state()).isEqualTo("INVALID");
            assertThat(result.personalInfo().get(field).alternatives()).hasSize(1);
            assertThat(result.personalInfo().get(field).normalized()).isNull();
        }
    }

    @Test
    void summaryPreservesParagraphAndStopsAtNextHeading() throws Exception {
        var field =
                parse(
                                "Profile\nFirst synthetic sentence.\nSecond sentence.\nSkills\nNever part of summary.")
                        .personalInfo()
                        .get("summary");
        assertThat(field.raw()).isEqualTo("First synthetic sentence.\nSecond sentence.");
        assertThat(field.evidence()).hasSize(2);
    }

    @Test
    void repeatedPageHeaderDeduplicatesAndRetainsBothSourcePages() throws Exception {
        var input =
                new Extraction(
                        1,
                        2,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(
                                new Page(1, "ok", "Email: a@example.test"),
                                new Page(2, "ok", "Email: a@example.test")));
        var field = map(input, config()).personalInfo().get("email");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.alternatives()).hasSize(1);
        assertThat(field.evidence()).extracting(e -> e.page()).containsExactly(1, 2);
    }

    @Test
    void unsupportedAndPartialSourcesRetainSourceSemantics() throws Exception {
        var unsupported =
                map(
                        new Extraction(
                                1,
                                2,
                                "legacy",
                                "success",
                                true,
                                List.of(new Page(1, "ok", "Gender: F"))),
                        config());
        assertThat(unsupported.reason()).isEqualTo("LEGACY_UNPAGED");
        assertThat(unsupported.personalInfo().get("gender").state()).isEqualTo("NOT_FOUND");
        var partial =
                map(
                        new Extraction(
                                1,
                                2,
                                "cv-compass.ocr/2.0",
                                "partial",
                                false,
                                List.of(
                                        new Page(1, "ok", "Email: a@example.test"),
                                        new Page(2, "failed", null))),
                        config());
        assertThat(partial.incompleteSource()).isTrue();
        assertThat(partial.personalInfo().get("email").normalized()).isEqualTo("a@example.test");
    }

    @Test
    void unlabelledNumbersAndDateOrDocumentLabelsAreNotPhoneCandidates() throws Exception {
        var result = parse("123456789\nDate: 2024-11-03\nDocument ID: 123456789");
        assertThat(result.personalInfo().get("phone").state()).isEqualTo("NOT_FOUND");
        for (String number :
                List.of(
                        "0912 345 678",
                        "+1 (202) 555-0199",
                        "020.1234.5678",
                        "+44 20 1234 5678 ext. 23")) {
            var field = parse("Phone: " + number).personalInfo().get("phone");
            assertThat(field.state()).as(number).isEqualTo("PROPOSED");
            assertThat(field.raw()).isEqualTo(number);
        }
    }

    @Test
    void unlabelledJobTitleWithContactEvidenceMustNotBecomeCandidateName() throws Exception {
        var base = config();
        var fields = new java.util.HashMap<>(base.personalFields());
        var original = fields.get("fullName");
        fields.put(
                "fullName",
                new PersonalInfoDefinition.FieldRule(
                        true, original.labels(), List.of("EXPLICIT_LABEL", "PROFILE_HEADER")));
        var definition =
                new PersonalInfoDefinition(
                        3,
                        base.engineCompatibility(),
                        base.revision(),
                        base.experience(),
                        fields,
                        base.sectionHeadings(),
                        base.excludedRegions());
        var field =
                map(input("Product Owner\nEmail: synthetic@example.test"), definition)
                        .personalInfo()
                        .get("fullName");
        assertThat(field.state()).isEqualTo("NOT_FOUND");
        assertThat(field.raw()).isNull();
    }

    @Test
    void yearRangeInContactSectionMustNotBecomePhone() throws Exception {
        var base = config();
        var fields = new java.util.HashMap<>(base.personalFields());
        var original = fields.get("phone");
        fields.put(
                "phone",
                new PersonalInfoDefinition.FieldRule(
                        true, original.labels(), List.of("EXPLICIT_LABEL", "SECTION_CONTEXT")));
        var definition =
                new PersonalInfoDefinition(
                        3,
                        base.engineCompatibility(),
                        base.revision(),
                        base.experience(),
                        fields,
                        base.sectionHeadings(),
                        base.excludedRegions());
        var field = map(input("Contact\n2024-2025"), definition).personalInfo().get("phone");
        assertThat(field.state()).isNotEqualTo("PROPOSED");
        assertThat(field.normalized()).isNull();
    }

    @Test
    void everyDisabledFieldAbstainsEvenWhenLabelledValueIsPresent() throws Exception {
        var base = config();
        var fields = new java.util.HashMap<>(base.personalFields());
        fields.replaceAll(
                (key, rule) ->
                        new PersonalInfoDefinition.FieldRule(
                                false, rule.labels(), rule.priorities()));
        var definition =
                new PersonalInfoDefinition(
                        3,
                        base.engineCompatibility(),
                        base.revision(),
                        base.experience(),
                        fields,
                        base.sectionHeadings(),
                        base.excludedRegions());
        var result =
                map(
                        input(
                                "Full name: Ada Example\nEmail: a@example.test\nPhone: +1 202 555 0199\nAddress: Example City.\nSummary\nSynthetic paragraph.\nGender: F"),
                        definition);
        assertThat(result.personalInfo()).hasSize(6);
        for (var field : result.personalInfo().values()) {
            assertThat(field.enabled()).isFalse();
            assertThat(field.state()).isEqualTo("NOT_FOUND");
            assertThat(field.raw()).isNull();
            assertThat(field.normalized()).isNull();
            assertThat(field.alternatives()).isEmpty();
            assertThat(field.evidence()).isEmpty();
            assertThat(field.issues()).extracting(issue -> issue.code()).contains("FIELD_DISABLED");
        }
    }

    @Test
    void invalidAlternativeDoesNotDisplaceUniqueValidValueOrLoseRawEvidence() throws Exception {
        var field =
                parse("Email: invalid@@example.test\nEmail: Local@EXAMPLE.TEST")
                        .personalInfo()
                        .get("email");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.raw()).isEqualTo("Local@EXAMPLE.TEST");
        assertThat(field.normalized()).isEqualTo("Local@example.test");
        assertThat(field.alternatives())
                .extracting(value -> value.raw())
                .containsExactly("invalid@@example.test", "Local@EXAMPLE.TEST");
        assertThat(field.issues()).extracting(issue -> issue.code()).contains("INVALID_EMAIL");
        assertThat(field.alternatives().getFirst().evidence()).hasSize(1);
    }

    @Test
    void allApprovedHonorificsAreRemovedWithoutCreatingGenderEvidence() throws Exception {
        for (String title : List.of("Mr", "Ms", "Mrs", "Ông", "Bà", "Anh", "Chị")) {
            var result = parse("Full name: " + title + " Ada Example");
            assertThat(result.personalInfo().get("fullName").raw())
                    .as(title)
                    .isEqualTo("Ada Example");
            assertThat(result.personalInfo().get("gender").state())
                    .as(title)
                    .isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void crlfAndAstralCharactersKeepAlternativeEvidenceInRawCodePointCoordinates()
            throws Exception {
        String raw =
                "😀\r\nGender: F\r\nSex: M\r\nEmail: First@example.test\r\nEmail: Second@example.test";
        var result = parse(raw);
        assertThat(result.personalInfo().get("gender").state()).isEqualTo("AMBIGUOUS");
        assertThat(result.personalInfo().get("email").state()).isEqualTo("AMBIGUOUS");
        for (var field : result.personalInfo().values())
            for (var alternative : field.alternatives())
                for (var evidence : alternative.evidence()) {
                    assertThat(evidence.page()).isEqualTo(1);
                    assertThat(
                                    raw.substring(
                                            raw.offsetByCodePoints(0, evidence.start()),
                                            raw.offsetByCodePoints(0, evidence.end())))
                            .isEqualTo(evidence.text());
                }
    }

    @Test
    void genericPersonalSubheadingsNeverReassignReferenceRecruiterOrCompanyOwnership()
            throws Exception {
        for (String owner :
                List.of("References", "Recruiter: Example Person", "Company: Example Workshop")) {
            var result =
                    parse(
                            owner
                                    + "\nContact Information\nEmail: excluded@example.test\n(020) 1234 5678\nPersonal Details\nGender: F\nProfile: Excluded summary.\nMore excluded text.");
            for (String field : List.of("email", "phone", "gender", "summary")) {
                assertThat(result.personalInfo().get(field).state())
                        .as(owner + "/" + field)
                        .isEqualTo("NOT_FOUND");
                assertThat(result.personalInfo().get(field).alternatives()).isEmpty();
            }
        }
    }

    @Test
    void inlineVietnameseSummaryKeepsDecomposedUnicodeAndFullWidthColonEvidence() throws Exception {
        String paragraph =
                java.text.Normalizer.normalize(
                        "Đoạn tổng hợp thứ nhất.", java.text.Normalizer.Form.NFD);
        String label = java.text.Normalizer.normalize("Tóm tắt", java.text.Normalizer.Form.NFD);
        String raw =
                "😀\n"
                        + label
                        + "： "
                        + paragraph
                        + "\nSecond synthetic sentence.\nSkills\nExcluded.";
        var field = parse(raw).personalInfo().get("summary");
        assertThat(field.state()).isEqualTo("PROPOSED");
        assertThat(field.raw()).isEqualTo(paragraph + "\nSecond synthetic sentence.");
        assertThat(field.evidence()).hasSize(2);
        for (var evidence : field.evidence()) {
            assertThat(
                            raw.substring(
                                    raw.offsetByCodePoints(0, evidence.start()),
                                    raw.offsetByCodePoints(0, evidence.end())))
                    .isEqualTo(evidence.text());
        }
    }

    private Extraction input(String raw) {
        return new Extraction(
                1, 2, "cv-compass.ocr/2.0", "success", false, List.of(new Page(1, "ok", raw)));
    }

    @Test
    void lineWrappedEmailRequiresClearBoundaryAndRetainsBothSpans() throws Exception {
        var field = parse("Email: ada@\nexample.test").personalInfo().get("email");
        assertThat(field.normalized()).isEqualTo("ada@example.test");
        assertThat(field.raw()).isEqualTo("ada@\nexample.test");
        assertThat(field.evidence()).hasSize(2);
        assertThat(parse("Email: ada\nexample.test").personalInfo().get("email").state())
                .isEqualTo("INVALID");
        assertThat(parse("Gender:\nF").personalInfo().get("gender").normalized())
                .isEqualTo("FEMALE");
        var phone = parse("Phone: +44 20 1234-\n5678").personalInfo().get("phone");
        assertThat(phone.normalized()).isEqualTo("+442012345678");
        assertThat(phone.evidence()).hasSize(2);
        assertThat(parse("Phone: 2024-\n11-03").personalInfo().get("phone").state())
                .isEqualTo("INVALID");
    }

    private PersonalInfoMappingResult parse(String raw) throws Exception {
        return map(
                new Extraction(
                        1,
                        2,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(new Page(1, "ok", raw))),
                config());
    }

    private PersonalInfoMappingResult map(Extraction input, PersonalInfoDefinition config) {
        return engine.map(
                new MappingClaim(
                        3,
                        1,
                        4,
                        config.revision(),
                        PersonalInfoDefinition.ENGINE_VERSION,
                        1,
                        UUID.randomUUID(),
                        Instant.now().plusSeconds(60),
                        config),
                input);
    }

    private PersonalInfoDefinition config() throws Exception {
        return (PersonalInfoDefinition)
                MappingVersions.config(
                        Files.readString(
                                Path.of("src/main/resources/mapping/personal-info-config-v3.json")),
                        JsonMapper.builder().build());
    }
}
