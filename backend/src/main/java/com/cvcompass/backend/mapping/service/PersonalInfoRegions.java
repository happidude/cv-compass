package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.dto.ExperienceDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import java.util.ArrayList;
import java.util.List;

final class PersonalInfoRegions {
    record Line(
            int page, int start, String text, String region, boolean uncertain, boolean heading) {}

    static List<Line> read(Extraction input, PersonalInfoDefinition config) {
        var result = new ArrayList<Line>();
        String region = "PERSONAL";
        for (var page : input.pages()) {
            if (!"ok".equals(page.status()) || page.rawText() == null) continue;
            boolean uncertain =
                    input.warnings().stream().anyMatch(PersonalInfoRegions::uncertain)
                            || page.warnings().stream().anyMatch(PersonalInfoRegions::uncertain)
                            || page.rawText().contains("\t")
                            || page.rawText().matches("(?s).*\\S {3,}\\S.*");
            int offset = 0;
            for (String raw : page.rawText().split("\\n", -1)) {
                String key = ExperienceDefinition.key(raw.strip().replaceAll("[:：]$", ""));
                String heading = null;
                if (config.experience().rules().experienceHeadings().stream()
                        .map(ExperienceDefinition::key)
                        .anyMatch(key::equals)) heading = "EXPERIENCE";
                if (config.experience().rules().stopHeadings().stream()
                        .map(ExperienceDefinition::key)
                        .anyMatch(key::equals)) heading = "EDUCATION";
                if (config.personalFields().get("summary").labels().stream()
                        .map(ExperienceDefinition::key)
                        .anyMatch(key::equals)) heading = "SUMMARY";
                for (var entry : config.sectionHeadings().entrySet()) {
                    if (entry.getValue().stream()
                            .map(ExperienceDefinition::key)
                            .anyMatch(key::equals)) heading = entry.getKey();
                }
                if (summaryLabels(config).stream()
                        .map(ExperienceDefinition::key)
                        .anyMatch(
                                label ->
                                        key.matches(
                                                java.util.regex.Pattern.quote(label)
                                                        + "\\s*[:：]\\s*\\S.*")))
                    heading = "SUMMARY";
                if (key.matches(
                        "(?:contact(?: details| information| data)?|thông tin liên hệ|chi tiết liên hệ)"))
                    heading = "CONTACT";
                if (key.matches(
                        "(?:(?:personal|candidate)(?: details| information| data)?|thông tin ứng viên|chi tiết cá nhân)"))
                    heading = "PERSONAL";
                if (heading == null
                        && key.matches(
                                "(?:(?:professional|career)(?: details| information| profile| overview)|curriculum vitae|resume|details|information|thông tin|chi tiết)"))
                    heading = "OTHER";
                // These ownership labels are not candidate contact labels.
                if (key.matches("(?:references?|referees?|người tham chiếu|người tham khảo)"))
                    heading = "REFERENCE";
                if (key.matches(
                        "(?:references?|referees?|người tham chiếu|người tham khảo)\\s*[:：].+"))
                    heading = "REFERENCE";
                if (key.matches("(?:recruiter|recruitment contact|nhà tuyển dụng)"))
                    heading = "RECRUITER";
                if (key.matches("(?:recruiter|recruitment contact|nhà tuyển dụng)\\s*[:：].+"))
                    heading = "RECRUITER";
                if (key.matches("(?:company contact|company details|thông tin công ty)"))
                    heading = "COMPANY";
                if (key.matches("(?:company|employer|công ty)\\s*[:：].+")) heading = "COMPANY";
                if (heading == null
                        && key.matches(
                                "(?:skills|projects|certifications|kỹ năng|dự án|chứng chỉ)"))
                    heading = "OTHER";
                if (heading != null) {
                    // Contact subheadings inside another owner's section do not transfer ownership.
                    if (List.of("REFERENCE", "RECRUITER", "COMPANY").contains(region)
                            && List.of("PERSONAL", "CONTACT", "SUMMARY").contains(heading))
                        heading = region;
                    region = heading;
                }
                boolean columns = uncertain || raw.contains("\t") || raw.matches(".*\\S {3,}\\S.*");
                result.add(new Line(page.number(), offset, raw, region, columns, heading != null));
                offset += raw.codePointCount(0, raw.length()) + 1;
            }
        }
        return List.copyOf(result);
    }

    private static boolean uncertain(String warning) {
        String key = warning.toLowerCase(java.util.Locale.ROOT);
        return key.contains("column") || key.contains("reading") || key.contains("order");
    }

    static List<String> summaryLabels(PersonalInfoDefinition config) {
        var labels = new ArrayList<>(config.personalFields().get("summary").labels());
        labels.addAll(config.sectionHeadings().get("SUMMARY"));
        return List.copyOf(labels);
    }

    static boolean personal(Line line, PersonalInfoDefinition definition) {
        return !definition.excludedRegions().contains(line.region())
                && !List.of("EXPERIENCE", "EDUCATION", "REFERENCE", "RECRUITER", "COMPANY", "OTHER")
                        .contains(line.region());
    }
}
