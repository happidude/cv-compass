package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Experience;
import com.cvcompass.backend.mapping.dto.ExperienceMappingResult.Period;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Frozen rules evaluated on a separate labeled synthetic split; no accuracy threshold is claimed. */
class ExperienceEvaluationTests {
    @Test
    void measuresHeldoutFieldsLinkageAndQualityWithoutTreatingUnknownAsNegativeSkill()
            throws Exception {
        var engine = new ExperienceEngine(new MappingEngine());
        var config = ExperienceFixtures.config();
        var fixtures = ExperienceFixtures.read("heldout-experiences.json");
        var development = ExperienceFixtures.read("development-experiences.json");
        assertThat(fixtures).allSatisfy(f -> assertThat(f.split()).isEqualTo("heldout"));
        assertThat(fixtures.stream().map(f -> f.id()).toList())
                .doesNotContainAnyElementsOf(development.stream().map(f -> f.id()).toList());
        var employer = new Counts();
        var title = new Counts();
        var period = new Counts();
        var linkage = new Counts();
        int predicted = 0,
                gold = 0,
                duplicate = 0,
                falseEmployer = 0,
                unknown = 0,
                ambiguous = 0,
                invalid = 0;
        int industryMapped = 0, functionMapped = 0, noExperienceDocuments = 0, overlap = 0;
        var cases = new ArrayList<Map<String, Object>>();
        long start = System.nanoTime();
        for (var fixture : fixtures) {
            var input = ExperienceFixtures.input(fixture);
            var result = engine.map(ExperienceFixtures.claim(config), input);
            ExperienceFixtures.verifySpans(result, input);
            assertThat(result.quality()).isEqualTo("NEEDS_REVIEW");
            var actual = result.experiences();
            var expected = fixture.expected();
            predicted += actual.size();
            gold += expected.size();
            List<String> actualEmployers =
                    actual.stream().map(e -> e.company().normalized()).toList();
            List<String> goldEmployers = expected.stream().map(e -> e.company()).toList();
            employer.add(actualEmployers, goldEmployers);
            title.add(
                    actual.stream()
                            .filter(e -> e.title().raw() != null)
                            .map(e -> ExperienceDefinition.key(e.title().raw()))
                            .toList(),
                    expected.stream()
                            .filter(e -> e.title() != null)
                            .map(e -> ExperienceDefinition.key(e.title()))
                            .toList());
            List<String> actualPeriods =
                    actual.stream()
                            .filter(e -> "PROPOSED".equals(e.period().state()))
                            .map(e -> signature(e.period()))
                            .toList();
            List<String> goldPeriods =
                    expected.stream()
                            .filter(e -> "PROPOSED".equals(e.state()) && e.start() != null)
                            .map(e -> e.start() + "/" + e.end() + "/" + e.ongoing())
                            .toList();
            period.add(actualPeriods, goldPeriods);
            List<String> actualLinks =
                    actual.stream()
                            .filter(
                                    e ->
                                            e.title().raw() != null
                                                    && "PROPOSED".equals(e.period().state()))
                            .map(ExperienceEvaluationTests::link)
                            .toList();
            List<String> goldLinks =
                    expected.stream()
                            .filter(
                                    e ->
                                            e.title() != null
                                                    && "PROPOSED".equals(e.state())
                                                    && e.start() != null)
                            .map(
                                    e ->
                                            e.company()
                                                    + "|"
                                                    + ExperienceDefinition.key(e.title())
                                                    + "|"
                                                    + e.start()
                                                    + "/"
                                                    + e.end()
                                                    + "/"
                                                    + e.ongoing())
                            .toList();
            linkage.add(actualLinks, goldLinks);
            var available = new HashMap<String, Integer>();
            for (String key : goldLinks) available.merge(key, 1, Integer::sum);
            var observed = new HashMap<String, Integer>();
            for (String key : actualLinks) {
                int count = observed.merge(key, 1, Integer::sum);
                if (count > available.getOrDefault(key, 0) && count > 1) duplicate++;
            }
            for (Experience item : actual) {
                if (!goldEmployers.contains(item.company().normalized())) falseEmployer++;
                for (String state :
                        List.of(
                                item.company().state(),
                                item.title().state(),
                                item.period().state())) {
                    if ("NOT_FOUND".equals(state)) unknown++;
                    if ("AMBIGUOUS".equals(state)) ambiguous++;
                    if ("INVALID".equals(state)) invalid++;
                }
                if (!item.company().industryCodes().isEmpty()) industryMapped++;
                if (!item.title().functionCodes().isEmpty()) functionMapped++;
                if (item.issues().stream()
                        .anyMatch(issue -> issue.code().equals("POSSIBLE_PERIOD_OVERLAP")))
                    overlap++;
            }
            if (!expected.isEmpty() && actual.isEmpty()) noExperienceDocuments++;
            cases.add(
                    Map.of(
                            "id",
                            fixture.id(),
                            "gold_experiences",
                            expected.size(),
                            "predicted_experiences",
                            actual.size(),
                            "source_incomplete",
                            result.incompleteSource()));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("dataset", "heldout-synthetic-v1");
        report.put("documents", fixtures.size());
        report.put("development_documents", development.size());
        report.put("gold_experiences", gold);
        report.put("predicted_experiences", predicted);
        report.put("employer", employer.report());
        report.put("title", title.report());
        report.put("period", period.report());
        report.put("linkage", linkage.report());
        report.put("duplicate_experiences", duplicate);
        report.put("false_employers", falseEmployer);
        report.put("unknown_fields", unknown);
        report.put("ambiguous_fields", ambiguous);
        report.put("invalid_fields", invalid);
        report.put("field_state_denominator", predicted * 3);
        report.put("industry_coverage", ratio(industryMapped, predicted));
        report.put("function_coverage", ratio(functionMapped, predicted));
        report.put("unparsed_documents_with_gold_experience", noExperienceDocuments);
        report.put("overlap_flagged_experiences", overlap);
        report.put("elapsed_ns", System.nanoTime() - start);
        report.put("cases", cases);
        Path output = Path.of("build/m10-3/heldout-metrics.json");
        Files.createDirectories(output.getParent());
        String body =
                JsonMapper.builder()
                        .build()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(report);
        Files.writeString(output, body);
        System.out.println("MAPPING_HELDOUT_METRICS " + body);
        assertThat(predicted).isPositive();
        assertThat(cases).hasSize(fixtures.size());
    }

    private static String signature(Period p) {
        return ExperienceFixtures.date(p.start())
                + "/"
                + ExperienceFixtures.date(p.end())
                + "/"
                + p.ongoingAsStated();
    }

    private static String link(Experience e) {
        return e.company().normalized()
                + "|"
                + ExperienceDefinition.key(e.title().raw())
                + "|"
                + signature(e.period());
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static final class Counts {
        int tp, fp, fn;

        void add(List<String> predicted, List<String> expected) {
            var remaining = new ArrayList<>(expected);
            for (String value : predicted) {
                if (remaining.remove(value)) tp++;
                else fp++;
            }
            fn += remaining.size();
        }

        Map<String, Object> report() {
            var result = new LinkedHashMap<String, Object>();
            result.put("tp", tp);
            result.put("fp", fp);
            result.put("fn", fn);
            result.put("precision", ratio(tp, tp + fp));
            result.put("recall", ratio(tp, tp + fn));
            return result;
        }
    }
}
