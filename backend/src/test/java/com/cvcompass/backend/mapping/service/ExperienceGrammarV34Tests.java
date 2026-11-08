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
