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

class ExperienceGrammarV34Tests {
    private static final String HEADINGS =
            """
            Work Experience
            Sales Manager / SAMPLE GROUP (Hose : SMP) Aug 2024 – Now
            Regional Sales Manager (SEA) Sep 2025 - Jul 2026
            SAMPLE CORP | Vietnam
            2020 – 2025 The Sample Tea House VN (Sample Group)
            2018 – 2020: Sample Group
            2025 – 2026 Công Ty Cổ Phần Mẫu
            Sample Works – Long An ( 02/2019 - 10/2024 )
            Jun 2018 - May 2020
            Logistics Coordinator
            SAMPLE LOGISTICS | Vietnam
            """;

    @Test
    void anchorsConsecutiveHeadingsAndPreservesTheirSourceValues() throws Exception {
        var rows = parse("3.4", HEADINGS).experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "SAMPLE GROUP (Hose : SMP)",
                        "SAMPLE CORP",
                        "The Sample Tea House VN (Sample Group)",
                        "Sample Group",
                        "Công Ty Cổ Phần Mẫu",
                        "Sample Works",
                        "SAMPLE LOGISTICS");
        assertThat(rows)
                .extracting(row -> row.period().alternatives().getFirst().raw())
                .containsExactly(
                        "Aug 2024 – Now",
                        "Sep 2025 - Jul 2026",
                        "2020 – 2025",
                        "2018 – 2020",
                        "2025 – 2026",
                        "02/2019 - 10/2024",
                        "Jun 2018 - May 2020");
        assertThat(rows.get(0).title().raw()).isEqualTo("Sales Manager");
        assertThat(rows.get(0).period().ongoingAsStated()).isTrue();
        assertThat(rows.get(1).title().raw()).isEqualTo("Regional Sales Manager (SEA)");
        assertThat(rows.get(2).issues())
                .extracting(issue -> issue.code())
                .contains("TITLE_NOT_FOUND");
        assertThat(rows.get(6).title().raw()).isEqualTo("Logistics Coordinator");
    }

    @Test
    void attachesParenthesizedPeriodToPrecedingCompany() throws Exception {
        var rows =
                parse("3.4", "Experience\nSample Works – Long An\n(December 2013—April 2017)")
                        .experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("Sample Works");
                            assertThat(row.period().start().month()).isEqualTo(12);
                            assertThat(row.period().end().month()).isEqualTo(4);
                        });
    }

    @Test
    void recognizesVietnameseCompanyPrefixWithParentheticalColon() throws Exception {
        var rows =
                parse("3.4", "Experience\nCty Mẫu (Chi nhánh : A)\nPeriod: 2021 – Present")
                        .experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("Cty Mẫu (Chi nhánh : A)");
                            assertThat(row.period().ongoingAsStated()).isTrue();
                        });
    }

    @Test
    void declinesDescriptionsAndStopsAtEducation() throws Exception {
        var result =
                parse(
                        "3.4",
                        """
                Experience
                Managed a large team in 2020 – 2025.
                - Wrote reports between 2019 and 2022.
                2012 – 2018 Vietnam
                Education
                2015 – 2018 Sample Academy
                """);
        assertThat(result.experiences()).isEmpty();
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains("AMBIGUOUS_EXPERIENCE_ROW");
    }

    @Test
    void parenthesesDoNotTurnEmployerTextIntoALabel() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Sales Manager / SAMPLE GROUP (Position : Mẫu) Aug 2024 – Now
                """)
                        .experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw())
                                    .isEqualTo("SAMPLE GROUP (Position : Mẫu)");
                            assertThat(row.title().raw()).isEqualTo("Sales Manager");
                        });
    }

    @Test
    void neitherLookaheadNorPrefixCrossesSectionBoundaries() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Employer: Sample Main Ltd
                Cty Mẫu
                Experience
                Sales Manager 2020 – 2022
                Consulting
                """)
                        .experiences();
        assertThat(rows).extracting(row -> row.company().raw()).containsExactly("Sample Main Ltd");
    }

    @Test
    void givesPipeAndSlashPriorityOverDashesOutsideParentheses() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Founder – GM Division / SAMPLE Vietnam 2013 - 2015
                CEO / SAMPLE-ONE - SAMPLE GROUP (Hose : SMP) Aug 2024 – Now
                Engineer | Sample-One – Sample Group (Team / Unit) 2020 – 2021
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly("Founder – GM Division", "CEO", "Engineer");
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "SAMPLE Vietnam",
                        "SAMPLE-ONE - SAMPLE GROUP (Hose : SMP)",
                        "Sample-One – Sample Group (Team / Unit)");
    }

    @Test
    void stopsContextAtCombinedEnglishAndVietnameseHeadings() throws Exception {
        for (String heading :
                List.of(
                        "EDUCATION AND CERTIFICATIONS",
                        "Education & Skills",
                        "Education/Skills",
                        "Education, Certifications",
                        "Học vấn và chứng chỉ")) {
            var result =
                    parse(
                            "3.4",
                            """
                    Consulting
                    Advisor / Sample Advisory 2020 – 2021
                    """
                                    + heading
                                    + "\n2012 – 2016 Sample Academy");
            assertThat(result.experiences()).isEmpty();
            assertThat(result.issues())
                    .as(heading)
                    .extracting(issue -> issue.code())
                    .filteredOn("UNCLASSIFIED_EXPERIENCE_CONTEXT"::equals)
                    .hasSize(1);
        }
    }

    @Test
    void requiresAnAdjacentAnchorForUndatedEmployersAndRejectsProseAndAddresses() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Engineer
                Sample Devices Ltd
                Handled reports for the sample team
                Orphan Sample Group
                Wrote weekly status notes
                The sample sales team shared its progress last week, Sample Group
                Period: 2018 – 2019
                250/9 Sample Road, Sample Group 2020 – 2021
                12 Example Street, Ward Sample Company
                Period: 2022 – 2023
                2024 – 2025: 12 Example Street, Phường Mẫu
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("Sample Devices Ltd");
        assertThat(rows.getFirst().title().raw()).isEqualTo("Engineer");
    }

    @Test
    void usesEntirePeriodHeadingAsTitleWhenNextLineLooksLikeAnEmployer() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Regional Manager | Northern Operations | Transformation 2020 – 2021
                SAMPLE SYSTEMS
                Sep 2022 – Jul 2023 Regional Manager | Partner Programs
                Sample Network | Long An
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "Regional Manager | Northern Operations | Transformation",
                        "Regional Manager | Partner Programs");
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("SAMPLE SYSTEMS", "Sample Network");
    }

    @Test
    void preservesDatedEmployersAndIndependentFollowingEmployers() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Education and Data Services 2019 – 2020
                2020 – 2021 Sample Tea House
                SAMPLE DEVICES LTD
                Position: Engineer
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "Education and Data Services", "Sample Tea House", "SAMPLE DEVICES LTD");
        assertThat(rows.get(1).title().state()).isEqualTo("NOT_FOUND");
        assertThat(rows.get(2).title().raw()).isEqualTo("Engineer");
    }

    @Test
    void doesNotReplaceAnInlineEmployerWithTheNextIndependentlyAnchoredEmployer() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Engineer | Sample Group 2020 – 2021
                SAMPLE DEVICES LTD
                Position: Engineer
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("Sample Group", "SAMPLE DEVICES LTD");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly("Engineer", "Engineer");
    }

    @Test
    void preservesUnclassifiedTitleTextAndAnUppercaseCompanyWithADash() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Service Operations | Digital Transformation 2020 – 2021
                SAMPLE-ONE - SAMPLE GROUP
                """)
                        .experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("SAMPLE-ONE - SAMPLE GROUP");
                            assertThat(row.title().raw())
                                    .isEqualTo("Service Operations | Digital Transformation");
                        });
    }

    @Test
    void keepsVerifiedPageContinuationWithoutAnAdjacentTitleOrPeriod() throws Exception {
        var result =
                parse(
                        "3.4",
                        List.of(
                                new Page(1, "ok", "Experience\n2019 – 2021 Sample Group\nEngineer"),
                                new Page(2, "ok", "Sample Group (continued)")));
        assertThat(result.experiences())
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.evidence())
                                        .anySatisfy(span -> assertThat(span.page()).isEqualTo(2)));
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .doesNotContain("ORPHAN_CONTINUATION");
    }

    @Test
    void combinedStopRequiresHeadingShape() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Work Experience
                2011 – 2012 Sample Alpha
                skills, and behaviors to become effective members of the team
                2013 – 2014 Sample Beta
                skills and training
                2015 – 2016 Sample Gamma
                Skills & Training.
                2017 – 2018 Sample Delta
                Skills and training, practice
                2019 – 2020 Sample Epsilon
                Skills and core training for new teams
                2021 – 2022 Sample Zeta
                EDUCATION AND CERTIFICATIONS
                2023 – 2024 Sample Academy
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "Sample Alpha",
                        "Sample Beta",
                        "Sample Gamma",
                        "Sample Delta",
                        "Sample Epsilon",
                        "Sample Zeta");
    }

    @Test
    void consecutiveDatedSummaryRowsAreIndependentAndMergeWithExactDetails() throws Exception {
        String summary =
                """
                Career Overview
                01/2010 – 12/2012: CÔNG TY MẪU A
                01/2013 – 12/2015: CÔNG TY MẪU B
                01/2016 – 12/2018: CÔNG TY MẪU C
                """;
        var rows = parse("3.4", summary).experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("CÔNG TY MẪU A", "CÔNG TY MẪU B", "CÔNG TY MẪU C");
        assertThat(rows).allSatisfy(row -> assertThat(row.title().raw()).isNull());

        var merged =
                parse(
                                "3.4",
                                summary
                                        + """
                Work Experience
                01/2010 – 12/2012: CÔNG TY MẪU A
                Sales Director
                01/2013 – 12/2015: CÔNG TY MẪU B
                Sales Manager
                01/2016 – 12/2018: CÔNG TY MẪU C
                Engineer
                """)
                        .experiences();
        assertThat(merged)
                .extracting(row -> row.company().raw())
                .containsExactly("CÔNG TY MẪU A", "CÔNG TY MẪU B", "CÔNG TY MẪU C");
        assertThat(merged)
                .extracting(row -> row.title().raw())
                .containsExactly("Sales Director", "Sales Manager", "Engineer");
        assertThat(merged)
                .allSatisfy(
                        row -> {
                            assertThat(row.period().state()).isEqualTo("PROPOSED");
                            assertThat(row.issues())
                                    .extracting(issue -> issue.code())
                                    .contains("SUMMARY_DETAIL_MERGED");
                        });
    }

    @Test
    void untitledSummaryDoesNotMergeAcrossDifferentEndDates() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Career Overview
                01/2010 – 12/2012: CÔNG TY MẪU
                Work Experience
                01/2010 – 12/2013: CÔNG TY MẪU
                Sales Director
                """)
                        .experiences();
        assertThat(rows).extracting(row -> row.period().end().year()).containsExactly(2012, 2013);
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly(null, "Sales Director");
    }

    @Test
    void doesNotConsumeACompanyWithItsOwnPeriodLabel() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Work Experience
                2013 – 2015 Sample One
                SAMPLE TWO LTD
                Period: 2016 – 2018
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("Sample One", "SAMPLE TWO LTD");
        assertThat(rows).extracting(row -> row.period().start().year()).containsExactly(2013, 2016);
    }

    @Test
    void doesNotConsumeACompanyWithItsOwnUnlabelledTitle() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Work Experience
                2013 – 2015 Sample One
                SAMPLE TWO LTD
                Engineer
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("Sample One", "SAMPLE TWO LTD");
        assertThat(rows).extracting(row -> row.title().raw()).containsExactly(null, "Engineer");
    }

    @Test
    void keepsTheCompanyOfATitleHeadingBeforeAnIndependentLabelledRow() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Work Experience
                Engineer 2013 – 2015
                SAMPLE ONE
                Company: Sample Two Ltd | Period: 2016 – 2018
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("SAMPLE ONE", "Sample Two Ltd");
        assertThat(rows).extracting(row -> row.title().raw()).containsExactly("Engineer", null);
    }

    @Test
    void periodBoundarySeparatorsDoNotOverrideDashesInsideTheHeading() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Work Experience
                Sales Director – Sample Asset JSC | 2017 – 2021
                Sales Manager – Sample Foods (Sample Group) | 2010 – 2012
                Earlier Career – Sample Vietnam | 1996 – 2007
                1993 – 1995 / Engineer – Sample Works
                Founder – GM Division / Sample Services | 1990 – 1992
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "Sample Asset JSC",
                        "Sample Foods (Sample Group)",
                        "Sample Vietnam",
                        "Sample Works",
                        "Sample Services");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "Sales Director",
                        "Sales Manager",
                        null,
                        "Engineer",
                        "Founder – GM Division");
        assertThat(rows)
                .extracting(row -> row.period().start().year())
                .containsExactly(2017, 2010, 1996, 1993, 1990);
    }

    @Test
    void acceptsLeadingPeriodsBeforeCommaEmployersAndMergesUppercaseDetails() throws Exception {
        String rows =
                """
                05/2018 – 09/2019: SAMPLE CO., LTD
                Sales Director
                11/2007 – 01/2011: SAMPLE B CO., LTD - HCM
                Sales Manager
                01/2006 – 10/2007: SAMPLE C CO., LTD
                Engineer
                02/2003 – 12/2005: SAMPLE D
                Analyst
                01/2001 – 12/2002: SAMPLE E
                Logistics Coordinator
                """;
        String summary = "Career Overview\n" + rows;
        var overview = parse("3.4", summary).experiences();
        assertThat(overview)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "SAMPLE CO., LTD",
                        "SAMPLE B CO., LTD",
                        "SAMPLE C CO., LTD",
                        "SAMPLE D",
                        "SAMPLE E");
        assertThat(overview)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "Sales Director",
                        "Sales Manager",
                        "Engineer",
                        "Analyst",
                        "Logistics Coordinator");
        var merged =
                parse(
                                "3.4",
                                summary
                                        + "Work Experience\n"
                                        + rows.toUpperCase(java.util.Locale.ROOT))
                        .experiences();
        assertThat(merged)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "SAMPLE CO., LTD",
                        "SAMPLE B CO., LTD",
                        "SAMPLE C CO., LTD",
                        "SAMPLE D",
                        "SAMPLE E");
        assertThat(merged)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "SALES DIRECTOR",
                        "SALES MANAGER",
                        "ENGINEER",
                        "ANALYST",
                        "LOGISTICS COORDINATOR");
        assertThat(merged)
                .allSatisfy(
                        row ->
                                assertThat(row.issues())
                                        .extracting(issue -> issue.code())
                                        .contains("SUMMARY_DETAIL_MERGED"));
    }

    @Test
    void rejectedRangeDoesNotLendItsTitleToAnotherEmployer() throws Exception {
        for (String rejected :
                List.of(
                        "05/2019 – 0/2020: SAMPLE X",
                        "Employer:\n05/2019 – 0/2020: SAMPLE X",
                        "2014 – 2015 Vietnam",
                        "Managed synthetic work in 2014 – 2015.")) {
            var result =
                    parse(
                            "3.4",
                            "Work Experience\n2012 – 2013 Sample Previous\n"
                                    + rejected
                                    + "\nEngineer\n2021 – 2022 Sample Next\nAnalyst");
            assertThat(result.experiences())
                    .as(rejected)
                    .extracting(row -> row.company().raw())
                    .containsExactly("Sample Previous", "Sample Next");
            assertThat(result.experiences())
                    .extracting(row -> row.title().raw())
                    .containsExactly(null, "Analyst");
            assertThat(result.issues())
                    .filteredOn(issue -> issue.code().equals("UNRESOLVED_EXPERIENCE_BLOCK"))
                    .singleElement()
                    .satisfies(
                            issue ->
                                    assertThat(issue.evidence())
                                            .extracting(span -> span.text())
                                            .containsExactly(
                                                    rejected.substring(
                                                            rejected.lastIndexOf('\n') + 1),
                                                    "Engineer"));
        }
    }

    @Test
    void explicitPeriodCanReadAValidWrappedRangeOnTheNextLine() throws Exception {
        var rows =
                parse(
                                "3.4",
                                "Work Experience\nCompany: Sample Ltd\nPeriod:\n"
                                        + "(2010 – 2012)\nEngineer")
                        .experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("Sample Ltd");
                            assertThat(row.period().start().year()).isEqualTo(2010);
                            assertThat(row.title().raw()).isEqualTo("Engineer");
                        });
    }

    @Test
    void legacyV33ReplaysSameInputByteForByte() throws Exception {
        assertThat(MappingDigests.sha256(MappingDigests.canonical(parse("3.3", HEADINGS))))
                .isEqualTo("2b65808003a5dcd4aa4d59ddedb13999456aaae37212d56cc1b8afa3f0df6433");
    }

    private static PersonalInfoMappingResult parse(String version, String raw) throws Exception {
        return parse(version, List.of(new Page(1, "ok", raw)));
    }

    private static PersonalInfoMappingResult parse(String version, List<Page> pages)
            throws Exception {
        PersonalInfoDefinition definition;
        try (var stream =
                ExperienceGrammarV34Tests.class.getResourceAsStream(
                        "/mapping/personal-info-config-v" + version + ".json")) {
            definition =
                    (PersonalInfoDefinition)
                            MappingVersions.config(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                                    JsonMapper.builder().build());
        }
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
