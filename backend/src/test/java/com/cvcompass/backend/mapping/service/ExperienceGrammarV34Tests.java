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
    void legacyV33ReplaysSameInputByteForByte() throws Exception {
        assertThat(MappingDigests.sha256(MappingDigests.canonical(parse("3.3", HEADINGS))))
                .isEqualTo("2b65808003a5dcd4aa4d59ddedb13999456aaae37212d56cc1b8afa3f0df6433");
    }

    private static PersonalInfoMappingResult parse(String version, String raw) throws Exception {
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
        var input =
                new Extraction(
                        1,
                        2,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(new Page(1, "ok", raw)));
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
