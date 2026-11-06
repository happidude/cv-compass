package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Experience;
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

class ExperienceGrammarTests {
    @Test
    void overviewDoesNotSuppressDifferentRoleOrEarlierPeriodAtTheSameEmployer() throws Exception {
        var result =
                parse(
                        "Career Overview\nExample Systems Ltd | Engineer | 2010 - 2012\nWork Experience\nExample Systems Ltd | Manager | 2020 - 2022");
        assertThat(result.experiences()).hasSize(2);
        assertThat(result.experiences())
                .extracting(row -> row.title().raw())
                .containsExactly("Engineer", "Manager");
        assertThat(result.experiences())
                .extracting(row -> row.period().start().year())
                .containsExactly(2010, 2020);
        assertThat(result.experiences())
                .allSatisfy(
                        row -> {
                            assertThat(row.period().state()).isEqualTo("PROPOSED");
                            assertThat(row.title().state()).isEqualTo("PROPOSED");
                            assertThat(row.evidence()).isNotEmpty();
                        });
    }

    @Test
    void applicationCompanyIsNotEmploymentWithoutAnEmploymentContext() throws Exception {
        var application =
                parse(
                        "Application for Example Systems Ltd\nPosition: Engineer\nEducation\nExample University");
        assertThat(application.experiences()).isEmpty();
        var explicit =
                parse("Employer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019 - 2021");
        assertThat(explicit.experiences()).hasSize(1);
        assertThat(explicit.experiences().getFirst().company().raw())
                .isEqualTo("Example Systems Ltd");
        assertThat(explicit.experiences().getFirst().title().raw()).isEqualTo("Engineer");
        assertThat(explicit.experiences().getFirst().period().state()).isEqualTo("PROPOSED");
    }

    @Test
    void syntheticCasesPreservePartialNegativeAndAmbiguousExperienceSemantics() throws Exception {
        try (var input =
                getClass().getResourceAsStream("/mapping/experience-grammar/bounded-cases.json")) {
            for (var test : JsonMapper.builder().build().readTree(input)) {
                var result = parse(test.path("raw").asString());
                assertThat(result.experiences())
                        .as(test.path("id").asString())
                        .hasSize(test.path("count").asInt());
                if (!test.path("sourceIssue").isMissingNode())
                    assertThat(result.issues())
                            .extracting(issue -> issue.code())
                            .contains(test.path("sourceIssue").asString());
                if (!result.experiences().isEmpty()) {
                    var row = result.experiences().getFirst();
                    assertThat(row.company().raw())
                            .as(test.path("id").asString())
                            .isEqualTo(test.path("company").asString());
                    assertThat(row.company().state()).isEqualTo("PROPOSED");
                    assertThat(row.title().raw()).isEqualTo(test.path("title").asString());
                    assertThat(row.period().state()).isEqualTo(test.path("period").asString());
                    assertThat(row.company().industryState()).isEqualTo("NOT_FOUND");
                    assertThat(row.evidence()).isNotEmpty();
                    if (!test.path("rowIssue").isMissingNode())
                        assertThat(row.issues())
                                .extracting(issue -> issue.code())
                                .contains(test.path("rowIssue").asString());
                }
            }
        }
    }

    @Test
    void grammarResultEnvelopeAndDigestAreDeterministicWithoutRunningLegacyHandlers()
            throws Exception {
        var pages =
                List.of(
                        new Page(
                                1,
                                "ok",
                                "Full name: Alex Example\nWork Experience\nExample Systems Ltd\nEngineer\n2019 - 2021"));
        var first = map(pages);
        assertThat(map(pages)).isEqualTo(first);
        var input = new Extraction(1, 2, "cv-compass.ocr/2.0", "success", false, pages);
        assertThat(first.inputSha256())
                .isEqualTo(MappingDigests.sha256(MappingDigests.canonical(input)));
        assertThat(first.engineVersion()).isEqualTo("personal-info-mapping/3.1");
        assertThat(first.schemaVersion()).isEqualTo(3);
        assertThat(first.quality()).isEqualTo("NEEDS_REVIEW");
        assertThat(first.incompleteSource()).isFalse();
        assertThat(first.reason()).isNull();
        assertThat(first.rulesRevision()).isEqualTo("synthetic-grammar-v31-r1");
    }

    @Test
    void proseEmployerMentionsAndReportingWithoutColonNeverBecomeAnotherRole() throws Exception {
        var rows =
                parse(
                                "Work Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019 - 2021\nReporting to CFO\nWorked with Other Supplier Ltd\nManaged a team as Engineer.")
                        .experiences();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().title().alternatives()).containsExactly("Engineer");
        assertThat(rows.getFirst().descriptionRaw())
                .contains("Reporting to CFO", "Other Supplier Ltd", "Managed a team");
    }

    @Test
    void explicitStatementsAndSingleYearDoNotInventEndDates() throws Exception {
        var statement =
                parse("Work Experience\nWorked at Example Workshop from 2019 to 2021")
                        .experiences()
                        .getFirst();
        assertThat(statement.company().raw()).isEqualTo("Example Workshop");
        assertThat(statement.period().start().year()).isEqualTo(2019);
        assertThat(statement.title().state()).isEqualTo("NOT_FOUND");
        var single =
                parse(
                                "Work Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019")
                        .experiences()
                        .getFirst();
        assertThat(single.period().start().precision()).isEqualTo("YEAR");
        assertThat(single.period().end()).isNull();
        assertThat(single.period().ongoingAsStated()).isFalse();
    }

    @Test
    void conflictingAndInvalidPeriodsRetainAlternativesRatherThanChoosing() throws Exception {
        var row =
                parse(
                                "Work Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019 - 2021 | Period: 2020 - 2022")
                        .experiences()
                        .getFirst();
        assertThat(row.period().state()).isEqualTo("AMBIGUOUS");
        assertThat(row.period().start()).isNull();
        assertThat(row.period().alternatives()).hasSize(2);
        assertThat(row.period().evidence()).hasSize(2);
        assertThat(row.issues()).extracting(issue -> issue.code()).contains("PERIOD_CONFLICT");
        var invalid =
                parse("Work Experience\nEmployer: Example Systems Ltd\nPeriod: 13/2019 - 14/2021")
                        .experiences()
                        .getFirst();
        assertThat(invalid.period().state()).isEqualTo("INVALID");
        assertThat(invalid.period().alternatives().getFirst().raw()).isEqualTo("13/2019 - 14/2021");
    }

    @Test
    void incompleteOverviewLinkAndCrossPageAnchorsRemainUnresolved() throws Exception {
        var overview =
                parse(
                        "Summary of Experience\nExample Systems Ltd | 2019 - 2021\nWork Experience\nExample Systems Ltd\nEngineer\n2019 - 2021");
        assertThat(overview.experiences()).hasSize(1);
        assertThat(overview.issues())
                .extracting(issue -> issue.code())
                .contains("AMBIGUOUS_SUMMARY_LINK");
        var split =
                map(
                        List.of(
                                new Page(1, "ok", "Work Experience\n2019 - 2021"),
                                new Page(2, "ok", "Example Systems Ltd\nEngineer")));
        assertThat(split.experiences()).hasSize(1);
        assertThat(split.experiences().getFirst().period().state()).isEqualTo("NOT_FOUND");
        assertThat(split.issues()).extracting(issue -> issue.code()).contains("UNLINKED_PAGE_TEXT");
    }

    @Test
    void bilingualAliasesMergeOnlyThroughDeclaredTaxonomyAndKeepRawAlternatives() throws Exception {
        var json = JsonMapper.builder().build();
        var node =
                (tools.jackson.databind.node.ObjectNode)
                        json.readTree(
                                Files.readString(
                                        Path.of(
                                                "src/main/resources/mapping/personal-info-config-v3.1.json")));
        var experience = (tools.jackson.databind.node.ObjectNode) node.path("experience");
        var company = experience.putArray("companies").addObject();
        company.put("canonical", "Example Systems").put("basis", "Synthetic configured employer");
        company.putArray("aliases").add("Example Systems Ltd").add("Công ty Ví dụ");
        company.putArray("industryCodes").add("SYNTHETIC_INDUSTRY");
        var title = experience.putArray("titles").addObject();
        title.put("canonical", "Engineer").put("seniorityCode", "SYNTHETIC_LEVEL");
        title.putArray("aliases").add("Engineer").add("Kỹ sư");
        title.putArray("functionCodes").add("SYNTHETIC_FUNCTION");
        var config =
                (PersonalInfoDefinition)
                        MappingVersions.config(json.writeValueAsString(node), json);
        var raw =
                "Summary of Experience\n2019 - 2021 | Công ty Ví dụ | Kỹ sư\nProfessional Experience\nExample Systems Ltd\nEngineer\n2019 - 2021";
        var rows = map(List.of(new Page(1, "ok", raw)), config).experiences();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().company().normalized()).isEqualTo("example systems");
        assertThat(rows.getFirst().company().industryCodes()).containsExactly("SYNTHETIC_INDUSTRY");
        assertThat(rows.getFirst().title().alternatives())
                .containsExactlyInAnyOrder("Engineer", "Kỹ sư");
        assertThat(rows.getFirst().title().functionCodes()).containsExactly("SYNTHETIC_FUNCTION");
    }

    @Test
    void typedBlockOrdersAndInlineSeparatorsHaveTheSameValuesWithOriginalEvidence()
            throws Exception {
        for (String body :
                List.of(
                        "2019 - 2021\nExample Systems Ltd\nSenior Engineer",
                        "Example Systems Ltd\nSenior Engineer\n2019 - 2021",
                        "Senior Engineer\nExample Systems Ltd\n2019 - 2021",
                        "2019 - 2021 | Example Systems Ltd | Senior Engineer",
                        "Example Systems Ltd | Senior Engineer | 2019 - 2021",
                        "Senior Engineer | Example Systems Ltd | 2019 - 2021",
                        "Example Systems Ltd (2019 - 2021)\nSenior Engineer",
                        "Senior Engineer / Example Systems Ltd\n2019 - 2021",
                        "Company: Example Systems Ltd (2019 - 2021)\nTitle: Senior Engineer",
                        "Company: Example Systems Ltd\nTitle: Senior Engineer (2019 - 2021)")) {
            var experiences = parse("Work Experience\n" + body).experiences();
            assertThat(experiences).as(body).hasSize(1);
            var row = experiences.getFirst();
            assertThat(row.company().normalized()).isEqualTo("example systems ltd");
            assertThat(row.title().raw()).isEqualTo("Senior Engineer");
            assertThat(row.title().state()).isEqualTo("PROPOSED");
            assertThat(row.period().state()).isEqualTo("PROPOSED");
            assertThat(row.period().start().year()).isEqualTo(2019);
            assertThat(row.period().start().precision()).isEqualTo("YEAR");
            assertThat(row.period().end().year()).isEqualTo(2021);
            assertThat(row.evidence()).isNotEmpty();
        }
    }

    @Test
    void employerPeriodWithoutTitleRemainsPartialAndNeverUsesHeaderRole() throws Exception {
        var result =
                parse("Engineering Director\nWork Experience\nExample Systems Ltd | 2019 - 2021");
        assertThat(result.experiences()).hasSize(1);
        var row = result.experiences().getFirst();
        assertThat(row.company().state()).isEqualTo("PROPOSED");
        assertThat(row.title().state()).isEqualTo("NOT_FOUND");
        assertThat(row.title().raw()).isNull();
        assertThat(row.issues()).extracting(issue -> issue.code()).contains("TITLE_NOT_FOUND");
    }

    @Test
    void reportingClientTrainingAndEducationDoNotBecomeCandidateEmployment() throws Exception {
        var result =
                parse(
                        "Education\nCEO Training\nExample School Ltd\n2019 - 2021\nWork Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2021 - 2023\nReporting to: CFO\nClient: Other Services Ltd\nSupplier: Vendor Ltd\nProject: Another Ltd");
        assertThat(result.experiences()).hasSize(1);
        var row = result.experiences().getFirst();
        assertThat(row.company().raw()).isEqualTo("Example Systems Ltd");
        assertThat(row.title().raw()).isEqualTo("Engineer");
        assertThat(row.title().alternatives()).containsExactly("Engineer");
        assertThat(row.descriptionRaw())
                .contains("Reporting to: CFO", "Client: Other Services Ltd");
    }

    @Test
    void multipleRolesDoNotInheritAnEmployerOuterPeriod() throws Exception {
        var rows =
                parse(
                                "Work Experience\nEmployer: Example Systems Ltd\nPeriod: 2010 - 2025\nPosition: Engineer\nPosition: Senior Engineer")
                        .experiences();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.period().state()).isEqualTo("NOT_FOUND"));
        assertThat(rows.getFirst().issues())
                .extracting(issue -> issue.code())
                .contains("EMPLOYER_PERIOD_NOT_ASSIGNED");
    }

    @Test
    void datedRolesAndReturnsStaySeparateAndOverlapIsNotRepaired() throws Exception {
        var rows =
                parse(
                                "Work Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019 - 2022\nPosition: Director\nPeriod: 2021 - 2023\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2024 - 2035")
                        .experiences();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2).period().end().year()).isEqualTo(2035);
        assertThat(rows.get(0).issues())
                .extracting(issue -> issue.code())
                .contains("POSSIBLE_PERIOD_OVERLAP");
        assertThat(rows.get(1).period().start().year()).isEqualTo(2021);
    }

    @Test
    void overviewAndDetailOnlyMergeAUniqueCompatibleRole() throws Exception {
        var result =
                parse(
                        "Summary of Experience\n2019 - 2021 | Example Systems Ltd | Engineer\nProfessional Experience\nExample Systems Ltd\nEngineer\n2019 - 2021\nBuilt synthetic systems.");
        assertThat(result.experiences()).hasSize(1);
        var row = result.experiences().getFirst();
        assertThat(row.issues())
                .extracting(issue -> issue.code())
                .contains("SUMMARY_DETAIL_MERGED");
        assertThat(row.descriptionRaw()).isEqualTo("Built synthetic systems.");
        assertThat(row.period().evidence()).hasSize(2);
    }

    @Test
    void continuationsRequireEmployerMarkerAndConsecutiveOkPages() throws Exception {
        var linked =
                map(
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "Work Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019 - 2021\nFirst task."),
                                new Page(
                                        2,
                                        "ok",
                                        "Employer: Example Systems Ltd (continued)\nSecond task.")));
        assertThat(linked.experiences()).hasSize(1);
        assertThat(linked.experiences().getFirst().descriptionRaw())
                .contains("First task.", "Second task.");
        var gap =
                map(
                        List.of(
                                new Page(
                                        1,
                                        "ok",
                                        "Work Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nFirst task."),
                                new Page(2, "failed", ""),
                                new Page(
                                        3,
                                        "ok",
                                        "Employer: Example Systems Ltd (continued)\nUnverified task.")));
        assertThat(gap.experiences()).hasSize(1);
        assertThat(gap.experiences().getFirst().descriptionRaw())
                .doesNotContain("Unverified task.");
        assertThat(gap.issues()).extracting(issue -> issue.code()).contains("ORPHAN_CONTINUATION");
    }

    @Test
    void periodBeforeEmployerAndMonthHyphenYearRemainOriginalSpans() throws Exception {
        var result =
                parse(
                        "Work History\nFrom: Jan-2019 To Now:\nCompany: Example Systems Ltd\nPosition: Engineer");
        assertThat(result.experiences()).hasSize(1);
        var period = result.experiences().getFirst().period();
        assertThat(period.state()).isEqualTo("PROPOSED");
        assertThat(period.start().month()).isEqualTo(1);
        assertThat(period.start().precision()).isEqualTo("MONTH");
        assertThat(period.end()).isNull();
        assertThat(period.ongoingAsStated()).isTrue();
        assertThat(period.evidence().getFirst().text()).contains("Jan-2019 To Now");
    }

    @Test
    void multilineFieldsKeepUnicodeAndCrlfSpans() throws Exception {
        var row =
                parse(
                                "😀\r\nWork Experience\r\nEmployer: Example\r\nSystems Ltd\r\nPosition: Senior\r\nSoftware Engineer\r\nPeriod: 2019 - 2021")
                        .experiences()
                        .getFirst();
        assertThat(row.company().raw()).isEqualTo("Example\r\nSystems Ltd");
        assertThat(row.company().evidence()).hasSize(2);
        assertThat(row.title().raw()).isEqualTo("Senior\r\nSoftware Engineer");
        assertThat(row.title().evidence()).hasSize(2);
    }

    @Test
    void unknownTwoColumnRelationsAreIssuesRatherThanInventedEmployers() throws Exception {
        var result = parse("Work Experience\n2019 - 2021\tExample Systems Ltd\tEngineer");
        assertThat(result.experiences()).isEmpty();
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains("AMBIGUOUS_EXPERIENCE_ROW");
    }

    @Test
    void ambiguousOverviewLinkRetainsAllRolesAndItsOriginalEvidence() throws Exception {
        var result =
                parse(
                        "Summary of Experience\n2019 - 2021 | Example Systems Ltd | Engineer\n"
                                + "Work Experience\nEmployer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019 - 2020\n"
                                + "Employer: Example Systems Ltd\nPosition: Engineer\nPeriod: 2019 - 2021");
        assertThat(result.experiences()).hasSize(3);
        assertThat(result.experiences())
                .allSatisfy(
                        row -> {
                            assertThat(row.company().raw()).isEqualTo("Example Systems Ltd");
                            assertThat(row.title().raw()).isEqualTo("Engineer");
                            assertThat(row.period().state()).isEqualTo("PROPOSED");
                            assertThat(row.period().evidence()).isNotEmpty();
                        });
        assertThat(result.experiences())
                .extracting(row -> row.period().end().year())
                .containsExactly(2021, 2020, 2021);
        assertThat(result.issues())
                .filteredOn(issue -> issue.code().equals("AMBIGUOUS_SUMMARY_LINK"))
                .singleElement()
                .satisfies(issue -> assertThat(issue.evidence()).isNotEmpty());
    }

    private PersonalInfoMappingResult parse(String raw) throws Exception {
        return map(List.of(new Page(1, "ok", raw)));
    }

    private PersonalInfoMappingResult map(List<Page> pages) throws Exception {
        var config =
                (PersonalInfoDefinition)
                        MappingVersions.config(
                                Files.readString(
                                        Path.of(
                                                "src/main/resources/mapping/personal-info-config-v3.1.json")),
                                JsonMapper.builder().build());
        return map(pages, config);
    }

    private PersonalInfoMappingResult map(List<Page> pages, PersonalInfoDefinition config) {
        var input =
                new Extraction(
                        1,
                        2,
                        "cv-compass.ocr/2.0",
                        pages.stream().allMatch(page -> page.status().equals("ok"))
                                ? "success"
                                : "partial",
                        false,
                        pages);
        var result =
                new PersonalInfoEngineV31()
                        .map(
                                new MappingClaim(
                                        3,
                                        1,
                                        4,
                                        config.revision(),
                                        PersonalInfoEngineV31.VERSION,
                                        1,
                                        UUID.randomUUID(),
                                        Instant.now().plusSeconds(60),
                                        config),
                                input);
        for (Experience row : result.experiences()) {
            row.evidence().forEach(span -> new MappingEngine().validateEvidence(input, span));
            row.company()
                    .evidence()
                    .forEach(span -> new MappingEngine().validateEvidence(input, span));
            row.title()
                    .evidence()
                    .forEach(span -> new MappingEngine().validateEvidence(input, span));
            row.period()
                    .evidence()
                    .forEach(span -> new MappingEngine().validateEvidence(input, span));
        }
        return result;
    }
}
