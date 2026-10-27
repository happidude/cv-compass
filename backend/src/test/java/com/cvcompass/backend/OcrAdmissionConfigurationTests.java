package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.ingestion.config.OcrAdmissionProperties;
import com.cvcompass.backend.ingestion.config.OcrIntegrationConfiguration;
import com.cvcompass.backend.ingestion.config.OcrJobConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OcrAdmissionConfigurationTests {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            OcrJobConfiguration.class, OcrIntegrationConfiguration.class);

    private ApplicationContextRunner enabled() {
        return context.withPropertyValues(
                "cv.ocr.enabled=true",
                "cv.ocr.integration.enabled=true",
                "cv.ocr.integration.namespace=local-test",
                "cv.storage.provider=local",
                "cv.storage.local.root-directory=" + System.getProperty("java.io.tmpdir"),
                "cv.ocr.integration.workers.worker.credential-sha256[0]=" + "a".repeat(64),
                "cv.ocr.integration.workers.worker.profiles[0]=test-v1",
                "cv.ocr.integration.profiles.test-v1.worker-commit=" + "b".repeat(40),
                "cv.ocr.integration.profiles.test-v1.image-id=sha256:" + "c".repeat(64),
                "cv.ocr.integration.profiles.test-v1.config-fingerprint=" + "d".repeat(64),
                "cv.ocr.integration.profiles.test-v1.engines-json={}");
    }

    @Test
    void defaultRemainsSyntheticOnly() {
        context.run(
                ctx ->
                        assertThat(ctx.getBean(OcrAdmissionProperties.class).getMode())
                                .isEqualTo(OcrAdmissionProperties.Mode.SYNTHETIC_ONLY));
        enabled().run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void localRequiresExplicitRegisteredProfileButNoFixtureManifest() {
        enabled()
                .withPropertyValues(
                        "cv.ocr.integration.admission.mode=local",
                        "cv.ocr.integration.admission.local-profile-revision=test-v1")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        for (String profile : new String[] {"", "unregistered"}) {
            enabled()
                    .withPropertyValues(
                            "cv.ocr.integration.admission.mode=local",
                            "cv.ocr.integration.admission.local-profile-revision=" + profile)
                    .run(ctx -> assertThat(ctx).hasFailed());
        }
    }

    @Test
    void invalidOrContradictoryModesAndProductionRemainRejected() {
        for (String mode : new String[] {"unknown", "synthetic-only"}) {
            enabled()
                    .withPropertyValues(
                            "cv.ocr.integration.admission.mode=" + mode,
                            "cv.ocr.integration.admission.local-profile-revision=test-v1")
                    .run(ctx -> assertThat(ctx).hasFailed());
        }
        enabled()
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "cv.ocr.integration.admission.mode=local",
                        "cv.ocr.integration.admission.local-profile-revision=test-v1")
                .run(ctx -> assertThat(ctx).hasFailed());
        enabled()
                .withPropertyValues(
                        "cv.ocr.enabled=false",
                        "cv.ocr.integration.admission.mode=local",
                        "cv.ocr.integration.admission.local-profile-revision=test-v1")
                .run(ctx -> assertThat(ctx).hasFailed());
        enabled()
                .withPropertyValues(
                        "cv.ocr.integration.admission.mode=local",
                        "cv.ocr.integration.admission.local-profile-revision=test-v2",
                        "cv.ocr.integration.profiles.test-v2.worker-commit=" + "b".repeat(40),
                        "cv.ocr.integration.profiles.test-v2.image-id=sha256:" + "c".repeat(64),
                        "cv.ocr.integration.profiles.test-v2.config-fingerprint=" + "d".repeat(64),
                        "cv.ocr.integration.profiles.test-v2.engines-json={}")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
