package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult;
import com.cvcompass.backend.mapping.dto.MappingDefinition;
import com.cvcompass.backend.mapping.dto.MappingResult;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExperienceEngineTests {
    private final ExperienceEngine engine = new ExperienceEngine(new MappingEngine());

    @Test
    void developmentCasesCoverSourceBindingAndQualitySemantics() {
        for (var fixture : ExperienceFixtures.read("development-experiences.json")) {
            var input = ExperienceFixtures.input(fixture);
            var result = engine.map(ExperienceFixtures.claim(ExperienceFixtures.config()), input);
            assertThat(result.experiences()).as(fixture.id()).hasSize(fixture.expected().size());
            for (int i = 0; i < fixture.expected().size(); i++) {
                var expected = fixture.expected().get(i);
                var actual = result.experiences().get(i);
                assertThat(actual.company().normalized())
                        .as(fixture.id() + " company")
                        .isEqualTo(expected.company());
                assertThat(actual.title().raw())
                        .as(fixture.id() + " title")
                        .isEqualTo(expected.title());
                assertThat(actual.period().state())
                        .as(fixture.id() + " state")
                        .isEqualTo(expected.state());
                assertThat(ExperienceFixtures.date(actual.period().start()))
                        .as(fixture.id() + " start")
                        .isEqualTo(expected.start());
                assertThat(ExperienceFixtures.date(actual.period().end()))
                        .as(fixture.id() + " end")
                        .isEqualTo(expected.end());
                assertThat(actual.period().ongoingAsStated()).isEqualTo(expected.ongoing());
            }
            assertThat(result.quality()).isEqualTo("NEEDS_REVIEW");
            ExperienceFixtures.verifySpans(result, input);
        }
    }

    @Test
    void repeatedCaseAndVerifiedBilingualTitleAliasRetainRawWithoutInventingNewRole() {
        var input =
                new Extraction(
                        2,
                        4,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "WORK EXPERIENCE\nEmployer: Northstar Devices\nTitle: HR Manager\nDates: 2011 - 2013"),
                                new Page(
                                        2,
                                        "ok",
                                        "Employer: Northstar Devices (continued)\nTitle: HR MANAGER\nChức danh: Trưởng phòng nhân sự\nSynthetic continuation.")));
        var result = engine.map(ExperienceFixtures.claim(ExperienceFixtures.config()), input);
        assertThat(result.experiences()).hasSize(1);
        var title = result.experiences().getFirst().title();
        assertThat(title.raw()).isEqualTo("HR Manager");
        assertThat(title.state()).isEqualTo("PROPOSED");
        assertThat(title.alternatives())
                .containsExactly("HR Manager", "HR MANAGER", "Trưởng phòng nhân sự");
        ExperienceFixtures.verifySpans(result, input);
    }

    @Test
    void undatedSummaryLinksOnlyUniqueMatchingDetailAndCoarseDatesRemainProposals() {
        var result =
                parse(
                        "CAREER SUMMARY\nEmployer: Meadow Labs\nTitle: HR Manager\nWORK EXPERIENCE\nEmployer: Meadow Labs\nTitle: HR Manager\nDates: 2018 - 2020");
        assertThat(result.experiences()).hasSize(1);
        assertThat(result.experiences().getFirst().issues())
                .extracting(issue -> issue.code())
                .contains("SUMMARY_DETAIL_MERGED");
        var ambiguous =
                parse(
                        "CAREER SUMMARY\nEmployer: Meadow Labs\nTitle: HR Manager\nWORK EXPERIENCE\nEmployer: Meadow Labs\nTitle: HR Manager\nDates: 2010 - 2012\nEmployer: Meadow Labs\nTitle: HR Manager\nDates: 2018 - 2020");
        assertThat(ambiguous.experiences()).hasSize(2);
        assertThat(ambiguous.issues())
                .extracting(issue -> issue.code())
                .contains("AMBIGUOUS_SUMMARY_LINK");
        var coarse =
                parse(
                        "CAREER SUMMARY\nEmployer: Meadow Labs\nTitle: HR Manager\nDates: 2018 - 2020\nWORK EXPERIENCE\nEmployer: Meadow Labs\nTitle: HR Manager\nDates: 05/2018 - 09/2020");
        assertThat(coarse.experiences()).hasSize(1);
        assertThat(coarse.experiences().getFirst().period().state()).isEqualTo("AMBIGUOUS");
        assertThat(coarse.experiences().getFirst().period().alternatives()).hasSize(2);
    }

    @Test
    void verifiedEmployerAliasCanContinueAcrossAdjacentOkPages() {
        var input =
                new Extraction(
                        2,
                        4,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "WORK EXPERIENCE\nEmployer: Northstar Devices\nTitle: QA Engineer\nDates: 2018 - 2020"),
                                new Page(
                                        2,
                                        "ok",
                                        "Employer: Northstar (continued)\nSynthetic alias continuation.")));
        var result = engine.map(ExperienceFixtures.claim(ExperienceFixtures.config()), input);
        assertThat(result.experiences()).hasSize(1);
        assertThat(result.experiences().getFirst().descriptionRaw())
                .contains("Synthetic alias continuation");
        assertThat(result.experiences().getFirst().evidence())
                .extracting(span -> span.page())
                .contains(1, 2);
        ExperienceFixtures.verifySpans(result, input);
    }

    private ExperienceMappingResult parse(String text) {
        return engine.map(
                ExperienceFixtures.claim(ExperienceFixtures.config()),
                new Extraction(
                        2,
                        4,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(new Page(1, "ok", text))));
    }

    @Test
    void companyIndustryAndTitleFunctionRemainIndependentAndNeverInferCurrentEmployment() {
        var result =
                parse(
                        "WORK EXPERIENCE\nEmployer: Unlisted Synthetic Workshop\nTitle: HR Manager\nDates: March 2014 until now\nCompany has 6,500 employees; manage 40 staff.\nClient: ClientCo\nSupplier: VendorCo");
        assertThat(result.experiences()).hasSize(1);
        var experience = result.experiences().getFirst();
        assertThat(experience.company().industryCodes()).isEmpty();
        assertThat(experience.company().industryState()).isEqualTo("NOT_FOUND");
        assertThat(experience.title().functionCodes()).containsExactly("HR");
        assertThat(experience.period().end()).isNull();
        assertThat(experience.period().ongoingAsStated()).isTrue();
        assertThat(experience.issues())
                .extracting(issue -> issue.code())
                .contains("ONGOING_AS_STATED_UNVERIFIED");
        assertThat(experience.descriptionRaw()).contains("6,500", "40", "ClientCo", "VendorCo");
        assertThat(experience.title().seniorityCode()).isEqualTo("MANAGER");
    }

    @Test
    void invalidCalendarDatesAndConflictingTitleProposalsAreNotRepaired() {
        var result =
                parse(
                        "WORK EXPERIENCE\nEmployer: Unknown Labs\nTitle: QA Engineer\nTitle: HR Director\nDates: 31/02/2020 - 01/03/2021");
        var experience = result.experiences().getFirst();
        assertThat(experience.title().state()).isEqualTo("AMBIGUOUS");
        assertThat(experience.title().raw()).isNull();
        assertThat(experience.title().functionCodes()).isEmpty();
        assertThat(experience.title().alternatives()).containsExactly("QA Engineer", "HR Director");
        assertThat(experience.period().state()).isEqualTo("INVALID");
        assertThat(experience.period().alternatives().getFirst().raw()).contains("31/02/2020");
    }

    @Test
    void explicitContinuationNeedsAdjacentOkPagesAndMatchingEmployer() {
        var input =
                new Extraction(
                        2,
                        4,
                        "cv-compass.ocr/2.0",
                        "partial",
                        false,
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "WORK EXPERIENCE\nEmployer: Meadow Labs\nTitle: HR Manager\nDates: 2018 - 2020\nFirst synthetic paragraph."),
                                new Page(2, "failed", ""),
                                new Page(
                                        3,
                                        "ok",
                                        "Employer: Meadow Labs (continued)\nUnlinked paragraph.")));
        var result = engine.map(ExperienceFixtures.claim(ExperienceFixtures.config()), input);
        assertThat(result.experiences()).hasSize(1);
        assertThat(result.incompleteSource()).isTrue();
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains("ORPHAN_CONTINUATION", "SOURCE_INCOMPLETE");
        assertThat(result.experiences().getFirst().descriptionRaw()).doesNotContain("Unlinked");
        ExperienceFixtures.verifySpans(result, input);
    }

    @Test
    void unicodeNormalizationMapsEveryFieldBackToOriginalCodePointSpan() {
        var fixture =
                ExperienceFixtures.read("development-experiences.json").stream()
                        .filter(f -> f.id().equals("dev-unicode"))
                        .findFirst()
                        .orElseThrow();
        var input = ExperienceFixtures.input(fixture);
        var result = engine.map(ExperienceFixtures.claim(ExperienceFixtures.config()), input);
        var employer = result.experiences().getFirst().company();
        assertThat(employer.normalized()).isEqualTo("café 😀 factory");
        assertThat(employer.raw()).isEqualTo("Cafe\u0301 😀 Factory");
        var evidence = employer.evidence().getFirst();
        assertThat(evidence.end() - evidence.start())
                .isEqualTo(employer.raw().codePointCount(0, employer.raw().length()));
        ExperienceFixtures.verifySpans(result, input);
    }

    @Test
    void englishDayPrecisionAndNonOverlappingReturnsRetainDistinctPeriods() {
        var result =
                parse(
                        "WORK EXPERIENCE\nEmployer: Meadow Labs\nTitle: QA Engineer\nDates: 1 January 2020 - 29 February 2020");
        assertThat(ExperienceFixtures.date(result.experiences().getFirst().period().start()))
                .isEqualTo("2020-01-01:DAY");
        assertThat(ExperienceFixtures.date(result.experiences().getFirst().period().end()))
                .isEqualTo("2020-02-29:DAY");
    }

    @Test
    void oldConfigCanonicalBytesAndOldResultReaderStayAtV1() {
        var old =
                new MappingDefinition(
                        1,
                        List.of(
                                new MappingDefinition.Field(
                                        "sample", "STRING", "PAGE", "FIRST_LINE", null)));
        String expected =
                "{\"schemaVersion\":1,\"engineCompatibility\":\"literal-mapping/1.0\",\"fields\":[{\"key\":\"sample\",\"type\":\"STRING\",\"scope\":\"PAGE\",\"extractor\":\"FIRST_LINE\",\"argument\":null}]}";
        assertThat(MappingDigests.canonical(old)).isEqualTo(expected);
        assertThat(MappingVersions.config(expected, JsonMapper.builder().build())).isEqualTo(old);
        var claim =
                new com.cvcompass.backend.mapping.dto.MappingClaim(
                        1,
                        2,
                        3,
                        "old",
                        MappingEngine.VERSION,
                        1,
                        java.util.UUID.randomUUID(),
                        java.time.Instant.MAX,
                        old);
        var result =
                new MappingEngine()
                        .map(
                                claim,
                                new Extraction(
                                        2,
                                        4,
                                        "cv-compass.ocr/2.0",
                                        "success",
                                        false,
                                        List.of(new Page(1, "ok", "Synthetic"))));
        // Golden bytes compiled independently from commit 0a0a733, not the new reader.
        var goldenResult =
                new MappingResult(
                        1,
                        2,
                        "cv-compass.ocr/2.0",
                        3,
                        "old",
                        MappingEngine.VERSION,
                        "NEEDS_REVIEW",
                        false,
                        null,
                        List.of(
                                new MappingResult.Value(
                                        "sample",
                                        "Synthetic",
                                        new MappingResult.Evidence(2, 1, 0, 9, "Synthetic"))),
                        "0".repeat(64),
                        4,
                        null);
        String goldenBody =
                "{\"schemaVersion\":1,\"extractionId\":2,\"ocrSchemaVersion\":\"cv-compass.ocr/2.0\",\"configId\":3,\"configRevision\":\"old\",\"engineVersion\":\"literal-mapping/1.0\",\"quality\":\"NEEDS_REVIEW\",\"incompleteSource\":false,\"reason\":null,\"values\":[{\"field\":\"sample\",\"value\":\"Synthetic\",\"evidence\":{\"extractionId\":2,\"page\":1,\"start\":0,\"end\":9,\"text\":\"Synthetic\"}}],\"inputSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"documentId\":4,\"profileRevision\":null}";
        assertThat(MappingDigests.canonical(goldenResult)).isEqualTo(goldenBody);
        assertThat(MappingVersions.result(goldenBody, JsonMapper.builder().build()))
                .isEqualTo(goldenResult);
        String body = MappingDigests.canonical(result);
        assertThat(body).doesNotContain("\"experiences\"", "\"issues\"", "\"taxonomyRevision\"");
        assertThat(MappingVersions.result(body, JsonMapper.builder().build()))
                .isInstanceOf(MappingResult.class)
                .isEqualTo(result);
    }

    @Test
    void unsupportedConfigVersionsAndTaxonomyConflictsFailValidation() {
        var config = ExperienceFixtures.config();
        var wrong =
                new ExperienceDefinition(
                        2,
                        "literal-mapping/1.0",
                        config.rulesRevision(),
                        config.taxonomyRevision(),
                        config.rules(),
                        config.titles(),
                        config.companies());
        assertThatThrownBy(wrong::validate).isInstanceOf(IllegalArgumentException.class);
        var duplicate =
                new ExperienceDefinition(
                        2,
                        ExperienceEngine.VERSION,
                        "r1",
                        "t1",
                        config.rules(),
                        List.of(config.titles().getFirst(), config.titles().getFirst()),
                        config.companies());
        assertThatThrownBy(duplicate::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                MappingVersions.config(
                                        "{\"schemaVersion\":3}", JsonMapper.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationRejectsScalarCoercionUnknownRulesAndCrossEngineInterpretation() {
        var json = JsonMapper.builder().build();
        var body =
                (tools.jackson.databind.node.ObjectNode)
                        json.readTree(ExperienceFixtures.resource("experience-config-v2.json"));
        body.put("script", "synthetic ignored script must not be accepted");
        assertThatThrownBy(() -> MappingVersions.config(body.toString(), json))
                .isInstanceOf(IllegalArgumentException.class);
        body.remove("script");
        ((tools.jackson.databind.node.ObjectNode) body.get("titles").get(0)).put("canonical", 123);
        assertThatThrownBy(() -> MappingVersions.config(body.toString(), json))
                .isInstanceOf(IllegalArgumentException.class);
        var v2 = ExperienceFixtures.claim(ExperienceFixtures.config());
        var mismatched =
                new com.cvcompass.backend.mapping.dto.MappingClaim(
                        v2.jobId(),
                        v2.extractionId(),
                        v2.configId(),
                        v2.configRevision(),
                        MappingEngine.VERSION,
                        v2.attempt(),
                        v2.token(),
                        v2.leaseExpiresAt(),
                        v2.definition());
        assertThatThrownBy(
                        () ->
                                new MappingEngine()
                                        .map(
                                                mismatched,
                                                new Extraction(
                                                        2,
                                                        4,
                                                        "cv-compass.ocr/2.0",
                                                        "success",
                                                        false,
                                                        List.of(new Page(1, "ok", "Synthetic")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlapsAndParentheticalEmployerNamesAreKeptWithoutDateOrEmployerRepair() {
        var result =
                parse(
                        """
                WORK EXPERIENCE
                Employer: Synthetic Holding (Division Label)
                Title: QA Engineer
                Dates: Jan 2007 - Dec 2008
                Employer: Synthetic Other Works
                Title: Program Manager
                Dates: Jan 2008 - March 2014
                """);
        assertThat(result.experiences()).hasSize(2);
        assertThat(result.experiences().getFirst().company().raw())
                .isEqualTo("Synthetic Holding (Division Label)");
        assertThat(result.experiences())
                .allSatisfy(
                        experience ->
                                assertThat(experience.issues())
                                        .extracting(issue -> issue.code())
                                        .contains("POSSIBLE_PERIOD_OVERLAP"));
        assertThat(ExperienceFixtures.date(result.experiences().get(1).period().start()))
                .isEqualTo("2008-01:MONTH");
        assertThat(ExperienceFixtures.date(result.experiences().getFirst().period().end()))
                .isEqualTo("2008-12:MONTH");
    }
}
