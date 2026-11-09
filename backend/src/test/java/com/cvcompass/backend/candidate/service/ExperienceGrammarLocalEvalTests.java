package com.cvcompass.backend.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.candidate.dto.ExperienceTotalsInput;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.service.PersonalInfoEngineV34;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ExperienceGrammarLocalEvalTests {
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Input(List<Page> pages, LocalDate importedAt) {}

    private record Totals(
            long files,
            long errors,
            long experiences,
            long companies,
            long periods,
            long titles,
            long months) {
        Totals plus(Totals other) {
            return new Totals(
                    files + other.files,
                    errors + other.errors,
                    experiences + other.experiences,
                    companies + other.companies,
                    periods + other.periods,
                    titles + other.titles,
                    months + other.months);
        }

        String line() {
            return ("CV_EVAL files=%d errors=%d experiences=%d companies=%d periods=%d "
                            + "titles=%d totalMonths=%d")
                    .formatted(files, errors, experiences, companies, periods, titles, months);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CV_EVAL_DIR", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "CI", matches = ".*")
    void evaluateLocalDirectory() {
        try {
            System.out.println(evaluate(Path.of(System.getenv("CV_EVAL_DIR"))).line());
        } catch (RuntimeException failure) {
            // Exceptions from local input can contain source text or a local path.
            System.out.println(new Totals(0, 1, 0, 0, 0, 0, 0).line());
        }
    }

    @Test
    void reportsOnlyCountsAndReusesProfileMonthRules(@TempDir Path directory) throws IOException {
        Files.writeString(
                directory.resolve("synthetic-private-marker.txt"),
                "Experience\n2010 – 2011 Sample Fabrication\nEngineer");
        Files.writeString(
                directory.resolve("synthetic-ocr.json"),
                """
                {"importedAt":"2020-06-30","ocr_result":{"pages":[
                  {"page_number":1,"status":"ok","raw_text":"Experience\\nJan 2020 – Present Sample Foundry\\nSales Manager"}
                ]}}
                """);
        Files.writeString(directory.resolve("broken.json"), "synthetic-invalid-content");
        assertThat(evaluate(directory).line())
                .isEqualTo(
                        "CV_EVAL files=3 errors=1 experiences=2 companies=2 periods=2 titles=2 totalMonths=30");
    }

    private static Totals evaluate(Path directory) {
        Totals result = new Totals(0, 0, 0, 0, 0, 0, 0);
        try (var entries = Files.list(directory)) {
            var definition = definition();
            var files =
                    entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .filter(
                                    path ->
                                            extension(path).endsWith(".txt")
                                                    || extension(path).endsWith(".json"))
                            .iterator();
            while (files.hasNext()) {
                try {
                    result = result.plus(evaluateFile(read(files.next()), definition));
                } catch (RuntimeException | IOException failure) {
                    result = result.plus(new Totals(1, 1, 0, 0, 0, 0, 0));
                }
            }
            return result;
        } catch (RuntimeException | IOException failure) {
            return result.plus(new Totals(0, 1, 0, 0, 0, 0, 0));
        }
    }

    private static String extension(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static PersonalInfoDefinition definition() throws IOException {
        try (var stream =
                ExperienceGrammarLocalEvalTests.class.getResourceAsStream(
                        "/mapping/personal-info-config-v3.4.json")) {
            return (PersonalInfoDefinition)
                    MappingVersions.config(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8), JSON);
        }
    }

    private static Input read(Path path) throws IOException {
        String text;
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Evaluation input too large");
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
        var pages = new ArrayList<Page>();
        if (extension(path).endsWith(".txt")) {
            for (var page : text.split("\\f", -1))
                pages.add(new Page(pages.size() + 1, page.isBlank() ? "blank" : "ok", page));
            return new Input(pages, null);
        }
        var root = JSON.readTree(text);
        var source = root.has("ocr_result") ? root.path("ocr_result") : root;
        if (!source.path("pages").isArray()) throw new IOException("Evaluation pages missing");
        for (var page : source.path("pages")) {
            int number = page.path("page_number").asInt();
            String status = page.path("status").asString();
            if (number != pages.size() + 1
                    || !List.of("ok", "blank", "failed", "not_processed").contains(status)
                    || "ok".equals(status) && !page.path("raw_text").isString())
                throw new IOException("Invalid evaluation page");
            pages.add(new Page(number, status, page.path("raw_text").asString("")));
        }
        var imported = root.path("importedAt");
        return new Input(pages, imported.isString() ? LocalDate.parse(imported.asString()) : null);
    }

    private static Totals evaluateFile(Input input, PersonalInfoDefinition definition) {
        boolean partial =
                input.pages.stream()
                        .anyMatch(
                                page -> List.of("failed", "not_processed").contains(page.status()));
        var extraction =
                new Extraction(
                        1,
                        1,
                        "cv-compass.ocr/2.0",
                        partial ? "partial" : "success",
                        false,
                        input.pages);
        var claim =
                new MappingClaim(
                        1,
                        1,
                        1,
                        definition.revision(),
                        PersonalInfoEngineV34.VERSION,
                        1,
                        new UUID(0, 0),
                        Instant.EPOCH,
                        definition);
        var rows = new PersonalInfoEngineV34().map(claim, extraction).experiences();
        var periods = new ArrayList<ExperienceTotalsInput>();
        long companies = 0, completePeriods = 0, titles = 0;
        for (var row : rows) {
            if (row.company().raw() != null && !row.company().raw().isBlank()) companies++;
            if ("PROPOSED".equals(row.title().state())) titles++;
            var period = row.period();
            var start = period.start();
            var end = period.end();
            if ("PROPOSED".equals(period.state())
                    && start != null
                    && (end != null || period.ongoingAsStated())) completePeriods++;
            periods.add(
                    new ExperienceTotalsInput(
                            new UUID(0, periods.size() + 1),
                            start == null ? null : start.earliest(),
                            start == null ? null : start.precision(),
                            end == null ? null : end.earliest(),
                            end == null ? null : end.precision(),
                            period.ongoingAsStated(),
                            period.state(),
                            false,
                            true,
                            1L));
        }
        var imports =
                input.importedAt == null ? Map.<Long, LocalDate>of() : Map.of(1L, input.importedAt);
        long months = ExperienceTotals.calculate(periods, imports).totalMonths();
        return new Totals(1, 0, rows.size(), companies, completePeriods, titles, months);
    }
}
