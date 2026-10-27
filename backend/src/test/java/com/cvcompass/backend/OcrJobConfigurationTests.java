package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.*;

import com.cvcompass.backend.ingestion.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OcrJobConfigurationTests {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(OcrJobConfiguration.class);

    @Test
    void defaultsAreDisabledAndApprovedTimingsAreTyped() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    var config = context.getBean(OcrJobProperties.class);
                    assertThat(config.isEnabled()).isFalse();
                    assertThat(config.getMaxAttempts()).isEqualTo(3);
                    assertThat(config.getLeaseDuration()).hasSeconds(120);
                    assertThat(config.getHeartbeatInterval()).hasSeconds(30);
                });
    }

    @Test
    void invalidConfigurationFailsInsteadOfBypassingInvariants() {
        for (String property :
                java.util.List.of(
                        "cv.ocr.max-attempts=4",
                        "cv.ocr.max-attempts=0",
                        "cv.ocr.lease-duration=0s",
                        "cv.ocr.heartbeat-interval=120s",
                        "cv.ocr.attempt-timeout=120s",
                        "cv.ocr.quarantine-grace=-1s",
                        "cv.ocr.retry-backoffs[0]=0s",
                        "cv.ocr.max-pages=51",
                        "cv.ocr.max-text-code-points=50001",
                        "cv.ocr.max-result-bytes=8388609",
                        "cv.ocr.expiry-batch-size=0")) {
            runner.withPropertyValues(property)
                    .run(context -> assertThat(context).as(property).hasFailed());
        }
    }

    @Test
    void productionCannotBeEnabled() {
        runner.withPropertyValues("spring.profiles.active=prod", "cv.ocr.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
