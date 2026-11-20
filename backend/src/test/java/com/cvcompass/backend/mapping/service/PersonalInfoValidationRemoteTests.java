package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonalInfoValidationRemoteTests {
    @Test
    void acceptsApprovedHonorificsAndAliasesWithExpectedNormalization() {
        Map<String, String> examples =
                Map.of(
                        "Mr Cao", "Cao",
                        "Mr. Cao", "Cao",
                        "Ms Lan", "Lan",
                        "Anh Tùng", "Tùng",
                        "NGUYỄN HOÀNG TÙNG (ETHAN)", "NGUYỄN HOÀNG TÙNG (ETHAN)",
                        "Nguyen Van A (Andy) ", "Nguyen Van A (Andy)");
        examples.forEach(
                (raw, normalized) ->
                        assertThat(PersonalInfoValidation.checkRemote("fullName", raw))
                                .as(raw)
                                .isEqualTo(new PersonalInfoValidation.Checked(normalized, null)));
    }

    @Test
    void rejectsInvalidNamesWithoutProducingNormalizedValues() {
        for (String raw :
                List.of(
                        "Cao",
                        "Mr",
                        "Engineer Nguyen",
                        "Nguyen (A",
                        "A (B) (C)",
                        "Nguyen 123",
                        "Nguyen (Andy) Van",
                        "Nguyen ((Andy))",
                        "Nguyen Andy)",
                        "Nguyen ()",
                        "Nguyen (123)",
                        "Nguyen (andy@example.test)",
                        "Nguyen https://example.test",
                        "Mr Engineer",
                        "Nguyen (Engineer)")) {
            assertThat(PersonalInfoValidation.checkRemote("fullName", raw))
                    .as(raw)
                    .isEqualTo(new PersonalInfoValidation.Checked(null, "INVALID_NAME"));
        }
    }

    @Test
    void supportsEveryRemoteHonorificWithCaseInsensitiveMatching() {
        for (String title :
                List.of(
                        "mr", "MS.", "Mrs", "Mrs.", "Miss", "Miss.", "Mx", "Mx.", "Dr", "Dr.",
                        "ÔNG", "bà", "anh", "CHỊ")) {
            assertThat(PersonalInfoValidation.checkRemote("fullName", title + " Cao"))
                    .as(title)
                    .isEqualTo(new PersonalInfoValidation.Checked("Cao", null));
        }
    }

    @Test
    void normalizesUnicodeAndWhitespaceWhilePreservingAliasCaseAndPunctuation() {
        String raw = Normalizer.normalize("  Chị\tTùng   (An-Dy O'Neil) \n", Normalizer.Form.NFD);
        assertThat(PersonalInfoValidation.checkRemote("fullName", raw))
                .isEqualTo(new PersonalInfoValidation.Checked("Tùng (An-Dy O'Neil)", null));
    }

    @Test
    void acceptsSingleWordWithAliasAndOrdinaryMultipleWordNames() {
        for (String raw : List.of("Cao (Andy)", "Nguyen Van A", "Jean-Luc O'Neil")) {
            assertThat(PersonalInfoValidation.checkRemote("fullName", raw))
                    .as(raw)
                    .isEqualTo(new PersonalInfoValidation.Checked(raw, null));
        }
    }

    @Test
    void retainsLengthLimitBeforeRemovingHonorifics() {
        for (String raw : List.of("", " \n\t", "A".repeat(256), "Mr " + "A".repeat(253))) {
            assertThat(PersonalInfoValidation.checkRemote("fullName", raw))
                    .isEqualTo(new PersonalInfoValidation.Checked(null, "VALUE_LENGTH_INVALID"));
        }
        assertThat(PersonalInfoValidation.checkRemote("fullName", "Mr " + "A".repeat(252)))
                .isEqualTo(new PersonalInfoValidation.Checked("A".repeat(252), null));
    }

    @Test
    void delegatesOtherFieldsToExistingValidation() {
        Map<String, String> examples =
                Map.of(
                        "email", "Local@EXAMPLE.TEST",
                        "phone", "+1 (202) 555-0199",
                        "gender", "Nữ",
                        "location", "  Synthetic City  ",
                        "summary", "  Synthetic\nparagraph.  ",
                        "dateOfBirth", "1990-05-15");
        examples.forEach(
                (field, raw) ->
                        assertThat(PersonalInfoValidation.checkRemote(field, raw))
                                .as(field)
                                .isEqualTo(PersonalInfoValidation.check(field, raw)));
    }

    @Test
    void sharedEngineValidationStillRejectsHonorificWithSingleWordName() {
        assertThat(PersonalInfoValidation.check("fullName", "Mr Cao"))
                .isEqualTo(new PersonalInfoValidation.Checked(null, "INVALID_NAME"));
    }
}
