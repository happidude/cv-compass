package com.cvcompass.backend.mapping.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MappingPropertiesTests {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(MappingConfiguration.class);

    @Test
    void overridesBindAndInvalidOperationalValuesFailStartup() {
        context.withPropertyValues(
                        "cv.mapping.scan-page-size=101",
                        "cv.mapping.max-attempts=5",
                        "cv.mapping.lease-duration=40s",
                        "cv.mapping.hard-deadline=60s",
                        "cv.mapping.retry-backoff=5s",
                        "cv.mapping.concurrency=2")
                .run(
                        app -> {
                            assertThat(app).hasNotFailed();
                            var properties = app.getBean(MappingProperties.class);
                            assertThat(properties.getScanPageSize()).isEqualTo(101);
                            assertThat(properties.getMaxAttempts()).isEqualTo(5);
                            assertThat(properties.getConcurrency()).isEqualTo(2);
                            assertThat(properties.getLeaseDuration())
                                    .isEqualTo(java.time.Duration.ofSeconds(40));
                        });
        for (String invalid :
                new String[] {
                    "scan-page-size=0",
                    "scan-page-size=10001",
                    "reaper-page-size=0",
                    "concurrency=0",
                    "concurrency=17",
                    "max-attempts=0",
                    "max-attempts=1001",
                    "scan-delay=0s",
                    "worker-delay=-1s",
                    "reaper-delay=2d",
                    "lease-duration=121s",
                    "hard-deadline=0s",
                    "retry-backoff=0s"
                }) {
            context.withPropertyValues("cv.mapping." + invalid)
                    .run(app -> assertThat(app).as(invalid).hasFailed());
        }
    }
}
