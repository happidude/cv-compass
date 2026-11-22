package com.cvcompass.backend.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NormalizationPropertiesTests {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(NormalizationConfiguration.class);

    @Test
    void normalizationLimitsBindIndependently() {
        context.withPropertyValues(
                        "cv.catalog.normalization.claim-max-items=7",
                        "cv.catalog.normalization.max-field-code-points=23",
                        "cv.catalog.normalization.candidate-scan-page-size=17",
                        "cv.catalog.normalization.max-attempts=4")
                .run(
                        app -> {
                            assertThat(app).hasNotFailed();
                            var properties = app.getBean(NormalizationProperties.class);
                            assertThat(properties.getClaimMaxItems()).isEqualTo(7);
                            assertThat(properties.getMaxFieldCodePoints()).isEqualTo(23);
                            assertThat(properties.getCandidateScanPageSize()).isEqualTo(17);
                            assertThat(properties.getMaxAttempts()).isEqualTo(4);
                        });
    }

    @Test
    void claimLimitAboveTheProtocolMaximumFailsBinding() {
        context.withPropertyValues("cv.catalog.normalization.claim-max-items=51")
                .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void invalidLimitsFailStartupEvenWhenWorkersAreDisabled() {
        for (String limit :
                new String[] {
                    "claim-max-items",
                    "max-field-code-points",
                    "candidate-scan-page-size",
                    "max-attempts"
                }) {
            context.withPropertyValues("cv.catalog.normalization." + limit + "=0")
                    .run(app -> assertThat(app).as(limit).hasFailed());
        }
    }
}
