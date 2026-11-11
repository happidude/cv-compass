package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExperienceGrammarV35Tests {
    private static final String HEAD_WITH_COMPANY = "Work Experience\nCompany: Mau Logistics JSC\n";

    private static final String NUMBERED =
            """
            Work Experience
            1. SAMPLE TRADING (03/2018 – Present)
            - Area Packaging Engineer
            2. Mau Foods JSC (01/2015 – 02/2018)
            - Sales Executive
            3) Tập Đoàn Mẫu Xanh (2012 – 2014)
            - Sales Assistant
            """;

    private static final String GROUP_TITLE =
            """
            Work Experience
            Mar 2017 – Present | GROUP SUPPLY DIRECTOR
            SAMPLE TRADING
            SAMPLE FOODS JSC
            Group Finance Director
            Mar 2016 – Dec 2020
            """;

    private static final String NEGATIVE =
            """
            Work Experience
            1. Tăng doanh số 2019 – 2020 ở vùng thử nghiệm
            2. Tăng doanh số (2019 – 2020) ở vùng phía Nam nhờ mở thêm đại lý mẫu.
            Group: Sample Holdings
            """;

    @Test
    void numberedPeriodHeadingsProduceExperiencesOnlyInV35() throws Exception {
        var rows = parse("3.5", NUMBERED).experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("SAMPLE TRADING", "Mau Foods JSC", "Tập Đoàn Mẫu Xanh");
        assertThat(rows)
                .extracting(row -> row.period().alternatives().getFirst().raw())
                .containsExactly("03/2018 – Present", "01/2015 – 02/2018", "2012 – 2014");
        assertThat(rows.getFirst().period().ongoingAsStated()).isTrue();
        assertThat(parse("3.4", NUMBERED).experiences()).isEmpty();
    }

    @Test
    void groupTitlesAreNotContextInV35() throws Exception {
        var rows = parse("3.5", GROUP_TITLE).experiences();
        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst().title().raw()).isEqualTo("GROUP SUPPLY DIRECTOR");
        assertThat(rows.getFirst().company().raw()).isEqualTo("SAMPLE TRADING");
        assertThat(rows)
                .extracting(row -> row.title() == null ? null : row.title().raw())
                .contains("Group Finance Director");
        assertThat(parse("3.4", GROUP_TITLE).experiences())
                .extracting(row -> row.title() == null ? null : row.title().raw())
                .doesNotContain("GROUP SUPPLY DIRECTOR", "Group Finance Director");
    }

    @Test
    void numberedDescriptionsAndGroupLabelsStillDoNotCreateExperiences() throws Exception {
        assertThat(parse("3.5", NEGATIVE).experiences()).isEmpty();
        assertThat(
                        parse("3.5", "Work Experience\nGroup: Sample Holdings\nSAMPLE TRADING\n")
                                .experiences())
                .isEmpty();
    }

    @Test
    void v35IsSupportedAndItsConfigParses() throws Exception {
        assertThat(PersonalInfoDefinition.supportsEngine("personal-info-mapping/3.5")).isTrue();
        assertThat(PersonalInfoDefinition.fieldNames("personal-info-mapping/3.5"))
                .isEqualTo(PersonalInfoDefinition.V33_FIELD_NAMES);
        var definition = definition("3.5");
        assertThat(definition.engineCompatibility()).isEqualTo(PersonalInfoEngineV35.VERSION);
        assertThat(definition.revision()).isEqualTo("personal-info-grammar-v35-r1");
    }

    @Test
    void legacyV34ReplaysSameInputByteForByte() throws Exception {
        assertThat(MappingDigests.sha256(MappingDigests.canonical(parse("3.4", NUMBERED))))
                .isEqualTo("63a4e19d55cab40864c2c3efdcb6a8381c1dced85022987a85fec701e74823c7");
    }

    @Test
    void degenerateNumberedLinesDoNotThrowOrCreateExperiences() throws Exception {
        for (var line :
                List.of("1. | (03/2018 – Present)", "1. : (2018 – 2019)", "1. :(2018 – 2019)")) {
            assertThat(parse("3.5", "Work Experience\n" + line + "\n").experiences())
                    .as(line)
                    .isEmpty();
        }
    }

    @Test
    void symbolBulletsAndThreeDigitNumbersKeepV34Behavior() throws Exception {
        for (var line :
                List.of(
                        "• SAMPLE TRADING (2018 – 2019)",
                        "- SAMPLE TRADING (2018 – 2019)",
                        "100. SAMPLE TRADING (2018 – 2019)")) {
            var text = "Work Experience\n" + line + "\n";
            assertThat(MappingDigests.canonical(parse("3.5", text).experiences()))
                    .as(line)
                    .isEqualTo(MappingDigests.canonical(parse("3.4", text).experiences()));
        }
        assertThat(parse("3.5", "Work Experience\n• SAMPLE TRADING (2018 – 2019)\n").experiences())
                .isEmpty();
    }

    @Test
    void personalInfoMatchesV34() throws Exception {
        var text =
                """
                Personal Information
                Full name: Mau Van Gia
                Email: mau@example.com
                Phone: 0900 000 001
                Address: 1 Sample Street, Sample City
                Date of birth: 01/02/1990
                Gender: Male
                """;
        assertThat(MappingDigests.canonical(parse("3.5", text).personalInfo()))
                .isEqualTo(MappingDigests.canonical(parse("3.4", text).personalInfo()));
    }

    @Test
    void groupWithoutColonInExperienceSection() throws Exception {
        for (var line : List.of("Group project kickoff for regional rollout", "Group Sales Team")) {
            var text = "Work Experience\n" + line + "\n";
            var v35 = MappingDigests.canonical(parse("3.5", text).experiences());
            var v34 = MappingDigests.canonical(parse("3.4", text).experiences());
            assertThat(v35).as(line).isEqualTo(v34).isEqualTo("[]");
        }
    }

    @Test
    void fromToColonRangeInPeriodLabel() throws Exception {
        for (var range : List.of("Mar-1999 To: Jun-2001", "Mar’- 1999 To: Jun-2001")) {
            var text =
                    "Work Experience\nFrom: "
                            + range
                            + "\nCompany: MAU RETAIL\nPosition: Sales Executive";
            var rows = parse("3.5", text).experiences();
            assertThat(rows).as(range).hasSize(1);
            assertThat(parse("3.4", text).experiences())
                    .as(range)
                    .noneMatch(
                            row ->
                                    row.period().alternatives().stream()
                                            .anyMatch(
                                                    a ->
                                                            a.end() != null
                                                                    && a.end().year() == 2001));
            var period = rows.getFirst().period();
            assertThat(period.state()).as(range).isEqualTo("PROPOSED");
            var start = period.alternatives().getFirst().start();
            var end = period.alternatives().getFirst().end();
            assertThat(start.year()).isEqualTo(1999);
            assertThat(start.month()).isEqualTo(3);
            assertThat(end.year()).isEqualTo(2001);
            assertThat(end.month()).isEqualTo(6);
        }
    }

    @Test
    void twoDigitYearRanges() throws Exception {
        for (var line :
                List.of(
                        "Feb 11 – Oct 12 Area Packaging Engineer",
                        "Sample Foundry Ltd | Feb 11 – Oct 12 Area Packaging Engineer")) {
            var text = "Work Experience\n" + line + "\nSample Foundry Ltd\n";
            var rows = parse("3.5", text).experiences();
            assertThat(rows).as(line).isNotEmpty();
            var period = rows.getFirst().period().alternatives().getFirst();
            assertThat(period.state()).isEqualTo("PROPOSED");
            assertThat(period.start().year()).isEqualTo(2011);
            assertThat(period.start().month()).isEqualTo(2);
            assertThat(period.end().year()).isEqualTo(2012);
            assertThat(period.end().month()).isEqualTo(10);
            assertThat(rows.getFirst().issues())
                    .extracting(issue -> issue.code())
                    .contains("TWO_DIGIT_YEAR_ASSUMED");
            assertThat(MappingDigests.canonical(parse("3.4", text).experiences()))
                    .as(line)
                    .doesNotContain("TWO_DIGIT_YEAR_ASSUMED");
        }
        var ongoing =
                parse(
                                "3.5",
                                "Work Experience\nSep 98 – Present Sales Executive\nSample Foundry Ltd\n")
                        .experiences();
        assertThat(ongoing).isNotEmpty();
        var value = ongoing.getFirst().period().alternatives().getFirst();
        assertThat(value.start().year()).isEqualTo(1998);
        assertThat(value.ongoingAsStated()).isTrue();
    }

    @Test
    void twoDigitYearNegativesStayAsV34() throws Exception {
        for (var line :
                List.of(
                        "May 21 staff joined",
                        "Top 10 – 20 accounts",
                        "Q1 23 – Q2 24",
                        "Jan 2020 – Mar 21 Area Packaging Engineer",
                        "Jun 15 – Jul 20 2020 Area Packaging Engineer",
                        "Jun 15, 2020 – Jul 20, 2020 Area Packaging Engineer",
                        "Dec 15 - 1991 - 1993 Area Packaging Engineer")) {
            var text = "Work Experience\nMAU RETAIL\n" + line + "\n";
            assertThat(MappingDigests.canonical(parse("3.5", text).experiences()))
                    .as(line)
                    .isEqualTo(MappingDigests.canonical(parse("3.4", text).experiences()))
                    .doesNotContain("TWO_DIGIT_YEAR_ASSUMED");
        }
        // Lone labelled dates never use the two-digit branch.
        for (var label : List.of("Period: May 21", "From: Mar 21")) {
            var text = "Work Experience\n" + label + "\nCompany: MAU RETAIL\nPosition: Engineer\n";
            assertThat(MappingDigests.canonical(parse("3.5", text).experiences()))
                    .as(label)
                    .isEqualTo(MappingDigests.canonical(parse("3.4", text).experiences()))
                    .doesNotContain("TWO_DIGIT_YEAR_ASSUMED");
        }
        // Start after end is never PROPOSED and raises no two-digit issue.
        var invalid =
                MappingDigests.canonical(
                        parse(
                                        "3.5",
                                        "Work Experience\nMAU RETAIL\nOct 12 – Feb 11 Area Packaging Engineer\n")
                                .experiences());
        assertThat(invalid).doesNotContain("PROPOSED", "TWO_DIGIT_YEAR_ASSUMED");
    }

    @Test
    void mixedFormTailKeepsLaterYearRange() throws Exception {
        var text = "Work Experience\nMAU RETAIL\nDec 15 - 1991 - 1993 Area Packaging Engineer\n";
        var rows = parse("3.5", text).experiences();
        assertThat(rows).isNotEmpty();
        var period = rows.getFirst().period().alternatives().getFirst();
        assertThat(period.start().year()).isEqualTo(1991);
        assertThat(period.end().year()).isEqualTo(1993);
    }

    @Test
    void twoDigitYearIssueOnlyOnRowUsingIt() throws Exception {
        var text =
                "Work Experience\nMAU RETAIL\nMar 2015 – Dec 2016 Sales Executive\n"
                        + "Sample Foundry Ltd\nFeb 11 – Oct 12 Area Packaging Engineer\n";
        var rows = parse("3.5", text).experiences();
        assertThat(rows).hasSize(2);
        var codes =
                rows.stream()
                        .map(row -> row.issues().stream().map(issue -> issue.code()).toList())
                        .toList();
        assertThat(codes.stream().filter(c -> c.contains("TWO_DIGIT_YEAR_ASSUMED")).count())
                .isEqualTo(1);
        var flagged =
                rows.stream()
                        .filter(
                                row ->
                                        row.issues().stream()
                                                .anyMatch(
                                                        i ->
                                                                i.code()
                                                                        .equals(
                                                                                "TWO_DIGIT_YEAR_ASSUMED")))
                        .findFirst()
                        .orElseThrow();
        assertThat(flagged.period().alternatives().getFirst().start().year()).isEqualTo(2011);
    }

    @Test
    void twoDigitYearCenturyBoundary() throws Exception {
        var high = parse("3.5", "Work Experience\nFeb 49 – Mar 49 Engineer\nSample Foundry Ltd\n");
        var low = parse("3.5", "Work Experience\nFeb 50 – Mar 50 Engineer\nSample Foundry Ltd\n");
        assertThat(high.experiences().getFirst().period().alternatives().getFirst().start().year())
                .isEqualTo(2049);
        assertThat(low.experiences().getFirst().period().alternatives().getFirst().start().year())
                .isEqualTo(1950);
    }

    @Test
    void toConnectorVariants() throws Exception {
        for (var range :
                List.of(
                        "Mar-1999 to: Jun-2001",
                        "Mar-1999 to : Jun-2001",
                        "Mar-1999 TO：Jun-2001")) {
            var text =
                    "Work Experience\nFrom: "
                            + range
                            + "\nCompany: MAU RETAIL\nPosition: Sales Executive";
            var period = parse("3.5", text).experiences().getFirst().period();
            assertThat(period.state()).as(range).isEqualTo("PROPOSED");
            assertThat(period.alternatives().getFirst().end().year()).as(range).isEqualTo(2001);
        }
    }

    @Test
    void fromToColonRangeUnchangedInV33() throws Exception {
        var text =
                "Work Experience\nFrom: Mar-1999 To: Jun-2001\nCompany: MAU RETAIL\nPosition: Sales Executive";
        assertThat(parse("3.3", text).experiences())
                .noneMatch(
                        row ->
                                row.period().alternatives().stream()
                                        .anyMatch(a -> a.end() != null && a.end().year() == 2001));
    }

    @Test
    void stopHeadingInSideColumnResumesExperienceInV35() throws Exception {
        var pages =
                List.of(
                        "Work Experience\nMau Logistics JSC\nPlant Supervisor | 03/2011 - 06/2014\n- Ran shift crews",
                        "Company: Sample Foundry Ltd\nEDUCATION\nSample Institute of Technology\nFaculty of Mechanics\n"
                                + "Bachelor of Engineering (2005 - 2009)\nQuality Director | 07/2014 - 09/2016");
        var rows = parsePages("3.5", pages).experiences();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).company().raw()).isEqualTo("Mau Logistics JSC");
        assertThat(rows.get(1).company().raw()).isEqualTo("Sample Foundry Ltd");
        assertThat(rows.get(1).title().raw()).isEqualTo("Quality Director");
        assertThat(rows.get(1).issues())
                .extracting(issue -> issue.code())
                .contains("SECTION_RESUMED_AFTER_STOP");
        assertThat(rows).noneMatch(row -> row.company().raw().contains("Sample Institute"));
        assertThat(MappingDigests.canonical(parsePages("3.4", pages).experiences()))
                .doesNotContain("Quality Director");
    }

    @Test
    void resumesWhenNoBlockWasOpenedBeforeStop() throws Exception {
        var pages =
                List.of(
                        "Work Experience\nPlant Supervisor | 03/2011 - 06/2014\n- Ran shift crews",
                        "EDUCATION\nSample Institute of Technology\nQuality Director | 07/2014 - 09/2016");
        var result = MappingDigests.canonical(parsePages("3.5", pages));
        assertThat(result).contains("SECTION_RESUMED_AFTER_STOP");
        assertThat(MappingDigests.canonical(parsePages("3.4", pages)))
                .doesNotContain("SECTION_RESUMED_AFTER_STOP");
    }

    @Test
    void resumeIgnoresEducationLinesAndJunkCompanies() throws Exception {
        var head = HEAD_WITH_COMPANY;
        var d =
                parse(
                                "3.5",
                                head
                                        + "EDUCATION\nQuality Director | 07/2014 - 09/2016\n"
                                        + "Bachelor of Engineering | 2005 - 2009\nSample Institute of Technology\n")
                        .experiences();
        assertThat(d).hasSize(1);
        assertThat(d.getFirst().company().raw()).isEqualTo("Mau Logistics JSC");
        assertThat(d.getFirst().title().raw()).isEqualTo("Quality Director");
        assertThat(d.getFirst().period().alternatives()).hasSize(1);
        var e =
                parse(
                                "3.5",
                                head
                                        + "EDUCATION\nQuality Director | 07/2014 - 09/2016\n"
                                        + "(2005 - 2009)\nSample Faculty of Languages\n")
                        .experiences();
        assertThat(e).isNotEmpty();
        assertThat(e)
                .allSatisfy(
                        row ->
                                assertThat(row.company().raw())
                                        .doesNotContain("Faculty", "2005")
                                        .isEqualTo("Mau Logistics JSC"));
        var teaching = head + "EDUCATION\nTeaching Assistant, Sample University | 2010 - 2012\n";
        assertThat(MappingDigests.canonical(parse("3.5", teaching)))
                .doesNotContain("SECTION_RESUMED_AFTER_STOP", "Teaching Assistant");
    }

    @Test
    void achievementsLabelBeforeAnyBlockKeepsSectionInV35() throws Exception {
        var text =
                "Work Experience\nAchievements:\nSample Foundry Ltd\nSales Executive | 2012 - 2015\n";
        assertThat(parse("3.5", text).experiences()).hasSize(1);
        assertThat(parse("3.4", text).experiences()).isEmpty();
    }

    @Test
    void achievementsLabelInsideJobDoesNotStopV35() throws Exception {
        var text =
                "Work Experience\nSample Foundry Ltd\nSales Executive | 2012 - 2015\nAchievements:\n"
                        + "- Opened 5 regional agents\nMAU LOGISTICS Jun 2015 – Feb 2017\nOperations Lead\n";
        var v35 = parse("3.5", text).experiences();
        assertThat(v35).hasSize(2);
        assertThat(v35.get(1).company().raw()).isEqualTo("MAU LOGISTICS");
        assertThat(parse("3.4", text).experiences()).hasSize(1);
    }

    @Test
    void stopHeadingDoesNotResumeWithoutRoleEvidence() throws Exception {
        var edu =
                "Work Experience\nSAMPLE TRADING\nSales Executive | 2012 - 2015\nEDUCATION\n"
                        + "Sample Institute of Technology\nBachelor of Engineering (2005 - 2009)\n";
        var refs =
                "Work Experience\nSAMPLE TRADING\nSales Executive | 2012 - 2015\nREFERENCES\n"
                        + "Sales Manager | 2010 - 2012\n";
        var noDetail = "EDUCATION\nQuality Director | 07/2014 - 09/2016\n";
        var refereeCompany =
                "Work Experience\nSample Foundry Ltd\nSales Executive | 2012 - 2015\nREFERENCES\n"
                        + "Company: Sample Referee Co., Ltd\nSales Manager | 2010 - 2012\n";
        for (var text : List.of(edu, refs, noDetail, refereeCompany)) {
            assertThat(parse("3.5", text).experiences().size()).as(text).isLessThanOrEqualTo(1);
            assertThat(MappingDigests.canonical(parse("3.5", text)))
                    .as(text)
                    .doesNotContain("SECTION_RESUMED_AFTER_STOP");
        }
        // A context heading between two stop headings ends the chance to resume.
        var interrupted =
                "Work Experience\nSample Foundry Ltd\nSales Executive | 2012 - 2015\nEDUCATION\n"
                        + "Projects\nSKILLS\nVolunteer Manager | 2015 - 2016\n";
        assertThat(MappingDigests.canonical(parse("3.5", interrupted)))
                .doesNotContain("SECTION_RESUMED_AFTER_STOP");
    }

    @Test
    void projectsLabelInsideJobIsUnchangedFromV34() throws Exception {
        var text =
                "Work Experience\nSample Foundry Ltd\nSales Executive | 2012 - 2015\nProjects:\n"
                        + "- Opened 5 regional agents\nMau Logistics JSC\nOperations Lead | 2015 - 2017\n";
        assertThat(MappingDigests.canonical(parse("3.5", text).experiences()))
                .isEqualTo(MappingDigests.canonical(parse("3.4", text).experiences()));
    }

    @Test
    void resumeAcrossPagesKeepsRestoredCompany() throws Exception {
        var pages =
                List.of(
                        "Work Experience\nCompany: Mau Logistics JSC",
                        "EDUCATION\nSample Institute of Technology\nQuality Director | 07/2014 - 09/2016");
        var rows = parsePages("3.5", pages).experiences();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().company().raw()).isEqualTo("Mau Logistics JSC");
        assertThat(rows.getFirst().title().raw()).isEqualTo("Quality Director");
    }

    @Test
    void educationWordsOnlyBlockStructuredRowsAfterResume() throws Exception {
        var head = HEAD_WITH_COMPANY + "EDUCATION\n";
        var market =
                parse("3.5", head + "Chuyên viên khảo sát thị trường mẫu | 2015 - 2018\n")
                        .experiences();
        assertThat(market).hasSize(1);
        assertThat(MappingDigests.canonical(market)).contains("SECTION_RESUMED_AFTER_STOP");
        var bullet =
                parse(
                                "3.5",
                                head
                                        + "Quality Director | 07/2014 - 09/2016\n"
                                        + "- Đào tạo đội ngũ tại Sample Academy\n")
                        .experiences();
        assertThat(bullet).hasSize(1);
        assertThat(MappingDigests.canonical(bullet)).contains("Sample Academy");
    }

    private static PersonalInfoDefinition definition(String version) throws Exception {
        try (var stream =
                ExperienceGrammarV35Tests.class.getResourceAsStream(
                        "/mapping/personal-info-config-v" + version + ".json")) {
            return (PersonalInfoDefinition)
                    MappingVersions.config(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                            JsonMapper.builder().build());
        }
    }

    private static PersonalInfoMappingResult parse(String version, String raw) throws Exception {
        return parsePages(version, List.of(raw));
    }

    private static PersonalInfoMappingResult parsePages(String version, List<String> texts)
            throws Exception {
        var definition = definition(version);
        var pages = new java.util.ArrayList<Page>();
        for (int i = 0; i < texts.size(); i++) pages.add(new Page(i + 1, "ok", texts.get(i)));
        var input = new Extraction(1, 2, "cv-compass.ocr/2.0", "success", false, pages);
        return PersonalInfoEngineV31.map(
                new MappingClaim(
                        3,
                        1,
                        4,
                        definition.revision(),
                        definition.engineCompatibility(),
                        1,
                        new UUID(0, 1),
                        Instant.EPOCH,
                        definition),
                input,
                definition.engineCompatibility());
    }
}
