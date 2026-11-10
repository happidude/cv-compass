package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.PersonalInfoDefinition;
import com.cvcompass.backend.mapping.dto.PersonalInfoMappingResult.PersonalField;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** All inputs are synthetic. Each case pins the 3.4 behavior and the 3.5 change side by side. */
class PersonalInfoGrammarV35Tests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void personalContactHeadingIsNotAFullNameCandidate() throws Exception {
        String cv =
                "MAU VAN GIA\nPERSONAL CONTACT\nEmail: mau@example.com\nPhone: 0900 000 001\n"
                        + "Work Experience\n09/2010 - 07/2014 Sample Trading";
        assertThat(field("3.4", cv, "fullName").state()).isEqualTo("AMBIGUOUS");
        var v35 = field("3.5", cv, "fullName");
        assertThat(v35.state()).isEqualTo("PROPOSED");
        assertThat(v35.normalized()).isEqualTo("MAU VAN GIA");
    }

    @Test
    void periodRangesAreNeverPhoneCandidates() throws Exception {
        String cv =
                "MAU VAN GIA\nContact\n04/1996 - 11/1999\n(1998 – 2001)\n"
                        + "Email: mau@example.com\nWork Experience\nSample Trading";
        assertThat(field("3.4", cv, "phone").state()).isNotEqualTo("NOT_FOUND");
        assertThat(field("3.5", cv, "phone").state()).isEqualTo("NOT_FOUND");
        for (String range :
                List.of(
                        "Phone: 1998 - 2001",
                        "Phone: (1998 – 2001)",
                        "Phone: 04/1996 - 11/1999",
                        "Phone: 1998 — 2001",
                        "Phone: 4/1996 - 11/1999",
                        "Phone: (02/2011 – 09/2014)")) {
            var v35 = field("3.5", "MAU VAN GIA\n" + range, "phone");
            assertThat(v35.state()).as(range).isEqualTo("INVALID");
            assertThat(v35.issues()).as(range).extracting(i -> i.code()).contains("INVALID_PHONE");
            assertThat(v35.normalized()).as(range).isNull();
        }
    }

    @Test
    void realPhoneShapesStayValid() throws Exception {
        for (String phone : List.of("0900 000 001", "+84 900 000 002", "(+84) 900.000.003")) {
            var v35 = field("3.5", "MAU VAN GIA\nPhone: " + phone, "phone");
            assertThat(v35.state()).as(phone).isEqualTo("PROPOSED");
            assertThat(v35.normalized()).as(phone).matches("[+]?[0-9]{9,12}");
        }
    }

    @Test
    void emailValueStopsAtPipeOrWhitespace() throws Exception {
        String glued = "MAU VAN GIA\nE-mail: mau@example.com| www.example.com/in/mau";
        assertThat(field("3.4", glued, "email").state()).isEqualTo("INVALID");
        var v35 = field("3.5", glued, "email");
        assertThat(v35.state()).isEqualTo("PROPOSED");
        assertThat(v35.normalized()).isEqualTo("mau@example.com");
        var spaced =
                field("3.5", "MAU VAN GIA\nEmail: mau@example.com www.example.com/in/mau", "email");
        assertThat(spaced.normalized()).isEqualTo("mau@example.com");
        assertThat(field("3.5", "MAU VAN GIA\nEmail: mau@example.com", "email").normalized())
                .isEqualTo("mau@example.com");
    }

    @Test
    void emailCutDoesNotShortenInvalidAddresses() throws Exception {
        for (String bad :
                List.of("a@example.xn--p1ai", "a@mail.example_corp.com", "a@gmail.com1")) {
            String cv = "MAU VAN GIA\nEmail: " + bad;
            var v34 = field("3.4", cv, "email");
            var v35 = field("3.5", cv, "email");
            assertThat(v35.state()).as(bad).isEqualTo(v34.state());
            assertThat(v35.normalized()).as(bad).isEqualTo(v34.normalized());
            assertThat(v35.normalized()).as(bad).isNotEqualTo("a@example.xn");
        }
        var plus = field("3.5", "MAU VAN GIA\nEmail: a+b@example.com| www.example.com", "email");
        assertThat(plus.state()).isEqualTo("PROPOSED");
        assertThat(plus.evidence().get(0).text()).isEqualTo("a+b@example.com");
    }

    @Test
    void keyHeadingEndsContactAreaButLaterLabelsAreRecorded() throws Exception {
        String cv = "MAU VAN GIA\nKEY SKILLS\nSales\nEmail: mau@example.com\nWork Experience";
        var v34 = field("3.4", cv, "email");
        var v35 = field("3.5", cv, "email");
        // Pinned: a labelled e-mail after KEY SKILLS is still recorded, identically in 3.4 and 3.5.
        assertThat(v34.state()).isEqualTo("PROPOSED");
        assertThat(v35.state()).isEqualTo("PROPOSED");
        assertThat(v35.normalized()).isEqualTo("mau@example.com");
    }

    @Test
    void trailingDashIsDroppedFromHeaderName() throws Exception {
        String cv = "MAU VAN GIA –\nPhone: 0900 000 001\nEmail: mau@example.com";
        assertThat(field("3.4", cv, "fullName").state()).isNotEqualTo("PROPOSED");
        var v35 = field("3.5", cv, "fullName");
        assertThat(v35.state()).isEqualTo("PROPOSED");
        assertThat(v35.normalized()).isEqualTo("MAU VAN GIA");
        assertThat(v35.evidence().get(0).text()).isEqualTo("MAU VAN GIA");
    }

    @Test
    void plainNameIsUnchanged() throws Exception {
        String cv = "Mau Van Gia\nPhone: 0900 000 001";
        assertThat(field("3.5", cv, "fullName").normalized())
                .isEqualTo(field("3.4", cv, "fullName").normalized())
                .isEqualTo("Mau Van Gia");
    }

    private static PersonalField field(String version, String raw, String name) throws Exception {
        var input =
                new Extraction(
                        1,
                        2,
                        "cv-compass.ocr/2.0",
                        "success",
                        false,
                        List.of(new Page(1, "ok", raw)));
        var definition = definition(version);
        var claim = claim(definition);
        var mapped =
                "3.5".equals(version)
                        ? new PersonalInfoEngineV35().map(claim, input)
                        : new PersonalInfoEngineV34().map(claim, input);
        var result = mapped.personalInfo().get(name);
        result.evidence().forEach(span -> new MappingEngine().validateEvidence(input, span));
        return result;
    }

    private static PersonalInfoDefinition definition(String version) throws Exception {
        try (var stream =
                PersonalInfoGrammarV35Tests.class.getResourceAsStream(
                        "/mapping/personal-info-config-v" + version + ".json")) {
            return (PersonalInfoDefinition)
                    MappingVersions.config(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8), JSON);
        }
    }

    private static MappingClaim claim(PersonalInfoDefinition definition) {
        return new MappingClaim(
                3,
                1,
                4,
                definition.revision(),
                definition.engineCompatibility(),
                1,
                new UUID(0, 1),
                Instant.EPOCH,
                definition);
    }
}
