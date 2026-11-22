package com.cvcompass.backend.mapping.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProvincesTests {
    @Test
    void resolvesCanonicalNamesAliasesAndRepeatedMatches() {
        assertThat(Provinces.canonical("District 1, HCMC")).isEqualTo("Hồ Chí Minh");
        assertThat(Provinces.canonical("Hanoi, Hà Nội")).isEqualTo("Hà Nội");
        assertThat(Provinces.canonical("Bà Rịa - Vũng Tàu")).isEqualTo("Bà Rịa - Vũng Tàu");
        assertThat(Provinces.canonical("Thừa Thiên Huế")).isEqualTo("Thừa Thiên Huế");
    }

    @Test
    void differentProvincesAreAmbiguousInEitherOrder() {
        assertThat(Provinces.canonical("Hà Nội / HCMC")).isNull();
        assertThat(Provinces.canonical("HCMC / Hà Nội")).isNull();
    }

    @Test
    void missingUnknownAndAmbiguousAbbreviationsDoNotInferAProvince() {
        assertThat(Provinces.canonical(null)).isNull();
        assertThat(Provinces.canonical(" ")).isNull();
        assertThat(Provinces.canonical("Synthetic remote location")).isNull();
        assertThat(Provinces.canonical("BD")).isNull();
    }
}
