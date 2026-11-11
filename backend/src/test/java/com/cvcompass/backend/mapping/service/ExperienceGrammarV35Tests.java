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
        var definition = definition(version);
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
