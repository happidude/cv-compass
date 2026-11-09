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

    private static final String R2B_SUMMARY =
            """
            Career Overview
            01/2010 – 12/2012: CÔNG TY MẪU A
            Sales Director
            01/2013 – 12/2015: CÔNG TY MẪU B
            Logistics Coordinator
            01/2016 – 12/2018: CÔNG TY MẪU C
            Paint Technician
            01/2019 – 12/2021: CÔNG TY MẪU D
            Engineering Manager
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
            assertThat(result.experiences())
                    .as(heading)
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.company().raw()).isEqualTo("Sample Advisory");
                                assertThat(row.issues())
                                        .extracting(issue -> issue.code())
                                        .contains("ROLE_CONTEXT_UNCLASSIFIED");
                            });
            assertThat(result.issues())
                    .as(heading)
                    .extracting(issue -> issue.code())
                    .doesNotContain("UNCLASSIFIED_EXPERIENCE_CONTEXT");
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
    void associatesTitlesBeforeAndAfterCompanyAnchorsWithoutReusingThem() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Sample Works – Long An ( 02/2019 - 10/2024 )
                Paint Technician
                Account Coordinator
                2017 – 2018 Sample Supply
                2014 – 2016 Sample Paint
                Planning Assistant
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("Sample Works", "Sample Supply", "Sample Paint");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly("Paint Technician", "Account Coordinator", "Planning Assistant");
        var wrapped =
                parse(
                                "3.4",
                                """
                Experience
                Sample Works – Long An
                (December 2013 – April 2017)
                Paint Technician
                """)
                        .experiences();
        assertThat(wrapped)
                .singleElement()
                .satisfies(row -> assertThat(row.title().raw()).isEqualTo("Paint Technician"));
    }

    @Test
    void parsesContextHeadingsWithAnIssueAndSkipsOtherContextLines() throws Exception {
        var result =
                parse(
                        "3.4",
                        """
                ADVISORY BOARD / SHARE HOLDER
                Sales Manager / Sample Advisory 2021 – 2022
                Sample Unanchored Ltd
                OTHER PROJECTS
                Regional Manager 2018 – 2020
                Sample Projects | Vietnam
                EDUCATION AND CERTIFICATIONS
                2012 – 2016 Sample Academy
                """);
        assertThat(result.experiences())
                .extracting(row -> row.company().raw())
                .containsExactly("Sample Advisory", "Sample Projects");
        assertThat(result.experiences())
                .allSatisfy(
                        row ->
                                assertThat(row.issues())
                                        .extracting(issue -> issue.code())
                                        .contains("ROLE_CONTEXT_UNCLASSIFIED"));
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains("UNCLASSIFIED_EXPERIENCE_CONTEXT");
    }

    @Test
    void keepsBookingAndCommaDelimitedDescriptionsOutOfTitles() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                2020 – 2021 Sample Service
                Booking hotel, restaurants, tickets for managers, director,
                Coordinating manager schedules
                                Shipping boxes for the manager
                Manager,
                Manager, Analyst, Director
                Manager for a very large region covering many business offices
                """)
                        .experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.title().state()).isEqualTo("NOT_FOUND");
                            assertThat(row.descriptionRaw())
                                    .contains("Booking hotel", "Coordinating manager");
                        });
    }

    @Test
    void doesNotMoveAnUnattachedTitleAcrossADescription() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Paint Technician
                Managed a team from 2017 – 2019.
                2020 – 2022 Sample Works
                """)
                        .experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.title().state()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void handlesBulletLabelsAndKeepsDatedRolesAtOneCompanySeparate() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                2016 – 2017 Sample Metals
                - Position: Technician
                2018 – 2020 Sample Metals
                • Chức danh: Giám sát
                Manager | 2021 – 2022
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("Sample Metals", "Sample Metals", "Sample Metals");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly("Technician", "Giám sát", "Manager");
        assertThat(rows)
                .extracting(row -> row.period().start().year())
                .containsExactly(2016, 2018, 2021);
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
    void assignsSeparateDetailTitlesBeforeMergingWithTheSummary() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Career Overview
                01/2010 – 12/2012: CÔNG TY MẪU A
                01/2013 – 12/2015: CÔNG TY MẪU B
                Work Experience
                01/2010 – 12/2012: CÔNG TY MẪU A
                Paint Technician
                Account Coordinator
                01/2013 – 12/2015: CÔNG TY MẪU B
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("CÔNG TY MẪU A", "CÔNG TY MẪU B");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly("Paint Technician", "Account Coordinator");
        assertThat(rows)
                .allSatisfy(
                        row ->
                                assertThat(row.issues())
                                        .extracting(issue -> issue.code())
                                        .contains("SUMMARY_DETAIL_MERGED"));
    }

    @Test
    void attachesAlternatingTitlesToFourPrecedingDatedEmployers() throws Exception {
        var rows = parse("3.4", R2B_SUMMARY).experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "CÔNG TY MẪU A", "CÔNG TY MẪU B", "CÔNG TY MẪU C", "CÔNG TY MẪU D");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "Sales Director",
                        "Logistics Coordinator",
                        "Paint Technician",
                        "Engineering Manager");
    }

    @Test
    void retainsThePrecedingUntitledDatedEmployerThroughNoiseAndMergesUppercaseDetails()
            throws Exception {
        var rows =
                parse(
                                "3.4",
                                R2B_SUMMARY
                                        + """
                Work Experience
                01/2010 – 12/2012: CÔNG TY MẪU A
                Page 1
                SALES DIRECTOR
                01/2013 – 12/2015: CÔNG TY MẪU B
                - Prepared synthetic team reports.
                LOGISTICS COORDINATOR
                01/2016 – 12/2018: CÔNG TY MẪU C
                PAINT TECHNICIAN
                01/2019 – 12/2021: CÔNG TY MẪU D
                ENGINEERING MANAGER
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "CÔNG TY MẪU A", "CÔNG TY MẪU B", "CÔNG TY MẪU C", "CÔNG TY MẪU D");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "SALES DIRECTOR",
                        "LOGISTICS COORDINATOR",
                        "PAINT TECHNICIAN",
                        "ENGINEERING MANAGER");
        assertThat(rows)
                .extracting(row -> row.period().start().year())
                .containsExactly(2010, 2013, 2016, 2019);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.title().state()).isEqualTo("PROPOSED");
                            assertThat(row.period().state()).isEqualTo("PROPOSED");
                            assertThat(row.issues())
                                    .extracting(issue -> issue.code())
                                    .contains("SUMMARY_DETAIL_MERGED");
                        });
    }

    @Test
    void keepsThePrecedingTitleLayoutForAnUndatedEmployer() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Work Experience
                2013 – 2015 Sample One
                - Prepared synthetic team reports.
                Paint Technician
                SAMPLE TWO LTD
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly("Sample One", "SAMPLE TWO LTD");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly(null, "Paint Technician");
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
    void continuesOneDatedEmployerAcrossFourAdjacentPages() throws Exception {
        var result =
                parse(
                        "3.4",
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "SYNTHETIC DOSSIER\nNgười Mẫu\nEXPERIENCE IN DETAILS\n"
                                                + "2018 – 2020 Sample Fabrication\nSample footer"),
                                new Page(
                                        2,
                                        "ok",
                                        "Page 2\nNgười Mẫu\nEXPERIENCE IN DETAILS\n"
                                                + "Paint Technician\n- Prepared synthetic parts.\nSample footer"),
                                new Page(
                                        3,
                                        "ok",
                                        "SYNTHETIC DOSSIER\nNgười Mẫu\nEXPERIENCE IN DETAILS\n"
                                                + "- Inspected synthetic parts.\nSample footer"),
                                new Page(
                                        4,
                                        "ok",
                                        "Page 4\nNgười Mẫu\nEXPERIENCE IN DETAILS\n"
                                                + "- Packed synthetic parts.\nSample footer")));
        assertThat(result.experiences())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("Sample Fabrication");
                            assertThat(row.title().raw()).isEqualTo("Paint Technician");
                            assertThat(row.period().start().year()).isEqualTo(2018);
                            assertThat(row.descriptionEvidence())
                                    .extracting(span -> span.page())
                                    .containsExactly(2, 3, 4);
                            assertThat(row.issues())
                                    .filteredOn(
                                            issue ->
                                                    issue.code()
                                                            .equals("PAGE_CONTINUATION_ASSUMED"))
                                    .hasSize(3);
                        });
    }

    @Test
    void doesNotAssignANewDatedTitleToThePreviousPageEmployer() throws Exception {
        var result =
                parse(
                        "3.4",
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "Experience in Details\n2018 – 2020 Sample Fabrication"),
                                new Page(
                                        2,
                                        "ok",
                                        "2022 – 2024 Paint Technician\n- Synthetic detail.")));
        assertThat(result.experiences())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.period().start().year()).isEqualTo(2018);
                            assertThat(row.title().state()).isEqualTo("NOT_FOUND");
                            assertThat(row.issues())
                                    .extracting(issue -> issue.code())
                                    .doesNotContain("PAGE_CONTINUATION_ASSUMED");
                        });
    }

    @Test
    void failedOrUnprocessedPagesBreakAssumedContinuation() throws Exception {
        for (String status : List.of("failed", "not_processed")) {
            var result =
                    parse(
                            "3.4",
                            List.of(
                                    new Page(
                                            1,
                                            "ok",
                                            "EXPERIENCE IN DETAILS\n2018 – 2020 Sample Fabrication"),
                                    new Page(2, status, ""),
                                    new Page(
                                            3,
                                            "ok",
                                            "EXPERIENCE IN DETAILS\nPaint Technician\n- Synthetic detail.")));
            assertThat(result.experiences())
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.title().state()).isEqualTo("NOT_FOUND");
                                assertThat(row.issues())
                                        .extracting(issue -> issue.code())
                                        .doesNotContain("PAGE_CONTINUATION_ASSUMED");
                            });
            assertThat(result.issues())
                    .extracting(issue -> issue.code())
                    .contains("UNLINKED_PAGE_TEXT");
        }
    }

    @Test
    void retainsLongParentheticalEmployerAndUsesSummaryTitleWhenDetailHasNone() throws Exception {
        String company = "SAMPLE CO., LTD (SAMPLE JEWELRY JOINT STOCK COMPANY)";
        var result =
                parse(
                        "3.4",
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "Career Overview\n05/2018 – 09/2019: "
                                                + company
                                                + "\nSales Manager\nEXPERIENCE IN DETAILS\n05/2018 – 09/2019: "
                                                + company),
                                new Page(2, "failed", ""),
                                new Page(3, "ok", "Sales Manager")));
        assertThat(result.experiences())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo(company);
                            assertThat(row.title().raw()).isEqualTo("Sales Manager");
                            assertThat(row.issues())
                                    .extracting(issue -> issue.code())
                                    .contains("SUMMARY_DETAIL_MERGED");
                        });
    }

    @Test
    void recognizesFlexibleHeadingsAndMergesSummaryAndDetail() throws Exception {
        for (String heading :
                List.of(
                        "Experience in Details",
                        "Employment History",
                        "Career History",
                        "Working History",
                        "Experiences",
                        "Quá trình công tác",
                        "Relevant Employment")) {
            String line = "Sales Manager | SAMPLE LTD | 2012 Sep – 2018 Dec\n";
            var rows =
                    parse("3.4", "Overview of Employment\n" + line + heading + "\n" + line)
                            .experiences();
            assertThat(rows)
                    .as(heading)
                    .singleElement()
                    .satisfies(
                            row ->
                                    assertThat(row.issues())
                                            .extracting(issue -> issue.code())
                                            .contains("SUMMARY_DETAIL_MERGED"));
        }
    }

    @Test
    void parsesExtendedDatesWithoutCorrectingMisspellings() throws Exception {
        record Example(
                String period,
                int year,
                int month,
                Integer endMonth,
                boolean ongoing,
                boolean prefix) {}
        for (var sample :
                List.of(
                        new Example("2023 Oct", 2023, 10, null, false, false),
                        new Example("2012 Sep – 2018 Dec", 2012, 9, 12, false, false),
                        new Example("2000 June- 2001 Mar", 2000, 6, 3, false, false),
                        new Example("Feb – 2017", 2017, 2, null, false, false),
                        new Example("Sep’- 2010", 2010, 9, null, false, false),
                        new Example("Tháng 3/ 2020", 2020, 3, null, false, false),
                        new Example("From Jan 2010 to Feb 2011", 2010, 1, 2, false, false),
                        new Example("From Mar 2011 until present", 2011, 3, null, true, false),
                        new Example("Decem 2010 – Septem 2011", 2010, 12, 9, false, true),
                        new Example("Octo 2010 – Nov 2011", 2010, 10, 11, false, true))) {
            var row =
                    parse(
                                    "3.4",
                                    "Experience in Summary\nEmployer: SAMPLE LTD\nPeriod: "
                                            + sample.period())
                            .experiences()
                            .getFirst();
            assertThat(row.period().state()).as(sample.period()).isEqualTo("PROPOSED");
            assertThat(row.period().start().year()).isEqualTo(sample.year());
            assertThat(row.period().start().month()).isEqualTo(sample.month());
            assertThat(row.period().end() == null ? null : row.period().end().month())
                    .isEqualTo(sample.endMonth());
            assertThat(row.period().ongoingAsStated()).isEqualTo(sample.ongoing());
            assertThat(
                            row.issues().stream()
                                    .anyMatch(
                                            issue ->
                                                    issue.code()
                                                            .equals(
                                                                    "MONTH_ABBREVIATION_NONSTANDARD")))
                    .isEqualTo(sample.prefix());
        }
        var invalid =
                parse("3.4", "Experience\nEmployer: SAMPLE LTD\nPeriod: Arp 2017 – May 2018")
                        .experiences()
                        .getFirst();
        assertThat(invalid.period().state()).isEqualTo("INVALID");
        assertThat(parse("3.4", "Experience\nSample Ltd Arp 2017 – May 2018").experiences())
                .isEmpty();
        var inline =
                parse("3.4", "Experience\nFrom Jan 2010 to Feb 2011: Sample Ltd").experiences();
        assertThat(inline)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("Sample Ltd");
                            assertThat(row.period().start().month()).isEqualTo(1);
                            assertThat(row.period().end().month()).isEqualTo(2);
                        });
    }

    @Test
    void ignoresMidSentenceLabelsAndContextButFindsTheThirdLinePosition() throws Exception {
        var result =
                parse(
                        "3.4",
                        """
                Experience
                Worked at Sample Foundry from Jan 2010 to Feb 2011
                Position and experiences in Sample Company:
                Location: SAMPLE CITY LTD
                - Position: Working as Paint Technician in a synthetic facility
                Category: SAMPLE CATEGORY LTD
                Report to: SAMPLE OFFICE LTD
                Industry: SAMPLE INDUSTRY LTD
                Địa chỉ: SAMPLE ADDRESS LTD
                """);
        assertThat(result.experiences())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("Sample Foundry");
                            assertThat(row.period().start().year()).isEqualTo(2010);
                            assertThat(row.title().raw()).isEqualTo("Paint Technician");
                            assertThat(row.title().alternatives())
                                    .contains(
                                            "Working as Paint Technician in a synthetic facility");
                        });
        var standalone =
                parse(
                        "3.4",
                        """
                Experience
                2012 – 2014 Sample Works
                Work as Sales Manager in a synthetic unit
                """);
        assertThat(standalone.experiences())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.title().raw()).isEqualTo("Sales Manager");
                            assertThat(row.title().alternatives())
                                    .contains("Work as Sales Manager in a synthetic unit");
                        });
    }

    @Test
    void keepsParentheticalLabelsLiteralOutsideAnExperienceSection() throws Exception {
        var rows = parse("3.4", "Company: Sample Ltd (A | Position: X)").experiences();
        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw())
                                    .isEqualTo("Sample Ltd (A | Position: X)");
                            assertThat(row.title().state()).isEqualTo("NOT_FOUND");
                        });
    }

    @Test
    void resolvesMixedDashAndPipeHeadingsWithLegalAndPeriodAdjacentEmployers() throws Exception {
        var rows =
                parse(
                                "3.4",
                                """
                Experience
                Regional Division – Northeast | Sales Director | SAMPLE ASSET JSC | 2017 – 2021
                Southern Division – Plant | Engineer | SAMPLE WORKS | 2013 – 2016
                Paint Technician – Sample Asset JSC | North Division | 2008 – 2011
                Earlier Career – Sample Vietnam | 1996 – 2007
                """)
                        .experiences();
        assertThat(rows)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "SAMPLE ASSET JSC", "SAMPLE WORKS", "Sample Asset JSC", "Sample Vietnam");
        assertThat(rows)
                .extracting(row -> row.title().raw())
                .containsExactly("Sales Director", "Engineer", "Paint Technician", null);
    }

    @Test
    void readsSingleLabelModifiersInFourBlocksAcrossThreePages() throws Exception {
        var result =
                parse(
                        "3.4",
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "Experience\n"
                                                + labelledEmployer(
                                                        "From", "Current", "A", "Position")),
                                new Page(
                                        2,
                                        "ok",
                                        labelledEmployer(
                                                        "Recent Period",
                                                        "Last",
                                                        "B",
                                                        "Previous Position")
                                                + labelledEmployer(
                                                        "From", "Former", "C", "Position")),
                                new Page(
                                        3,
                                        "ok",
                                        labelledEmployer("From", "Present", "D", "Position"))));
        assertThat(result.experiences())
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "SAMPLE A CO LTD.",
                        "SAMPLE B CO LTD.",
                        "SAMPLE C CO LTD.",
                        "SAMPLE D CO LTD.");
        assertThat(result.experiences())
                .allSatisfy(
                        row -> {
                            assertThat(row.title().raw()).isEqualTo("Division Manager");
                            assertThat(row.period().state()).isEqualTo("PROPOSED");
                            assertThat(row.period().start().year()).isEqualTo(2016);
                            assertThat(row.period().start().month()).isEqualTo(4);
                            assertThat(row.period().end().year()).isEqualTo(2017);
                            assertThat(row.period().end().month()).isEqualTo(2);
                        });
    }

    private static String labelledEmployer(
            String period, String modifier, String sample, String title) {
        return """
                %s: Apr - 2016 to Feb – 2017 :
                %s Company : SAMPLE %s CO LTD.
                Location : SAMPLE CITY
                Category : SAMPLE CATEGORY
                Report to : SAMPLE TEAM
                %s : Division Manager
                """
                .formatted(period, modifier, sample, title);
    }

    @Test
    void rejectsMultipleOrUnlistedModifiersAndMidSentenceLabels() throws Exception {
        var result =
                parse(
                        "3.4",
                        """
                Experience
                Worked at SAMPLE FOUNDRY from Jan 2010 to Feb 2011
                Current Previous Company: SAMPLE UNRELATED LTD.
                Future Company: SAMPLE OTHER LTD.
                Coordinated meetings with the previous company: SAMPLE THIRD LTD.
                Position: Division Manager
                """);
        assertThat(result.experiences())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("SAMPLE FOUNDRY");
                            assertThat(row.title().raw()).isEqualTo("Division Manager");
                        });
    }

    @Test
    void keepsEmploymentIntroductionsWithTheirPrecedingStatements() throws Exception {
        var result =
                parse(
                        "3.4",
                        """
                Experience
                Worked at SAMPLE A from July 2022 to Oct 2023.
                Position and experiences in SAMPLE A Company:
                Position: Program Manager.
                - Prepared synthetic reports.
                Worked at SAMPLE B from July 2022 to Oct 2023.
                Positions and experience at SAMPLE B Company:
                Location: SAMPLE CITY
                Category: SAMPLE CATEGORY
                Position: Account Manager.
                - Reviewed synthetic reports.
                Worked at SAMPLE C from July 2022 to Oct 2023.
                Experience at SAMPLE C 2018–2020 Company:
                Position: Sales Manager.
                - Filed synthetic reports.
                """);
        assertThat(result.experiences())
                .extracting(row -> row.company().raw())
                .containsExactly("SAMPLE A", "SAMPLE B", "SAMPLE C");
        assertThat(result.experiences())
                .extracting(row -> row.title().raw())
                .containsExactly("Program Manager.", "Account Manager.", "Sales Manager.");
        assertThat(result.experiences())
                .allSatisfy(
                        row -> {
                            assertThat(row.period().state()).isEqualTo("PROPOSED");
                            assertThat(row.period().start().year()).isEqualTo(2022);
                            assertThat(row.period().start().month()).isEqualTo(7);
                            assertThat(row.period().end().year()).isEqualTo(2023);
                            assertThat(row.period().end().month()).isEqualTo(10);
                        });
    }

    @Test
    void dottedSummaryTitlesMergeWithOngoingDetailOfTheSameEmployer() throws Exception {
        String summary =
                """
                Experience in Summary
                2023 Oct – Dec 2023 SAMPLE PACKING LTD. Sales Director.
                2022 Oct – Dec 2022 SAMPLE FABRICATION LTD. Engineer.
                2021 Oct – Dec 2021 SAMPLE WORKS. Scrum Master.
                2020 Oct – Dec 2020 SAMPLE NETWORK JSC. Logistics Coordinator.
                2019 Oct – Dec 2019 SAMPLE TOOLS CO. Analyst.
                2018 Oct – Dec 2018 SAMPLE PAINT CORP. Sales Manager.
                """;
        var overview = parse("3.4", summary).experiences();
        assertThat(overview)
                .extracting(row -> row.company().raw())
                .containsExactly(
                        "SAMPLE PACKING LTD.",
                        "SAMPLE FABRICATION LTD.",
                        "SAMPLE WORKS",
                        "SAMPLE NETWORK JSC.",
                        "SAMPLE TOOLS CO.",
                        "SAMPLE PAINT CORP.");
        assertThat(overview)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "Sales Director",
                        "Engineer",
                        "Scrum Master",
                        "Logistics Coordinator",
                        "Analyst",
                        "Sales Manager");
        var merged =
                parse(
                                "3.4",
                                summary
                                        + """
                Experience in Details
                2023 Oct – Present SAMPLE PACKING CO., LTD.
                2022 Oct – Present SAMPLE FABRICATION LTD.
                2021 Oct – Present SAMPLE WORKS
                2020 Oct – Present SAMPLE NETWORK JSC.
                2019 Oct – Present SAMPLE TOOLS CO.
                2018 Oct – Present SAMPLE PAINT CORP.
                """)
                        .experiences();
        assertThat(merged).hasSize(6);
        assertThat(merged)
                .extracting(row -> row.title().raw())
                .containsExactly(
                        "Sales Director",
                        "Engineer",
                        "Scrum Master",
                        "Logistics Coordinator",
                        "Analyst",
                        "Sales Manager");
        assertThat(merged.getFirst().company().raw()).isEqualTo("SAMPLE PACKING CO., LTD.");
        assertThat(merged)
                .allSatisfy(
                        row -> {
                            assertThat(row.period().state()).isEqualTo("PROPOSED");
                            assertThat(row.period().ongoingAsStated()).isTrue();
                            assertThat(row.issues())
                                    .extracting(issue -> issue.code())
                                    .contains("SUMMARY_DETAIL_MERGED");
                        });
    }

    @Test
    void reportsGenericOrDescriptiveEmployersWithoutCreatingExperience() throws Exception {
        for (String text :
                List.of(
                        "Project-based",
                        "Freelance",
                        "Remote",
                        "Full-time",
                        "Part-time",
                        "Contract",
                        "coordinated many sample reports and weekly meetings for the internal team",
                        "Coordinated many sample reports and weekly meetings for the internal team")) {
            var result = parse("3.4", "Experience\n2020 – 2021 " + text);
            assertThat(result.experiences()).as(text).isEmpty();
            assertThat(result.issues())
                    .extracting(issue -> issue.code())
                    .contains("AMBIGUOUS_EXPERIENCE_ROW");
        }
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
