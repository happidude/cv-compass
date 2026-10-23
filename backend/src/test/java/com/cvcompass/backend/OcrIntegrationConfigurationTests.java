package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.config.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class OcrIntegrationConfigurationTests {
    @Test void integrationDefaultsOffAndIndependentOfDbSeam() {
        new ApplicationContextRunner().withUserConfiguration(OcrJobConfiguration.class,OcrIntegrationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OcrIntegrationProperties.class).isEnabled()).isFalse();
                });
    }
    @Test void enabledWithoutEnrollmentIsRejected() {
        new ApplicationContextRunner().withUserConfiguration(OcrJobConfiguration.class,OcrIntegrationConfiguration.class)
                .withPropertyValues("cv.ocr.enabled=true","cv.ocr.integration.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void bodyCeilingAndTimingCannotBeBypassed() {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var properties=new OcrIntegrationProperties();
            properties.setControlBodyBytes(16385);
            properties.setDownloadTimeout(java.time.Duration.ZERO);
            assertThat(factory.getValidator().validate(properties)).hasSize(2);
        }
    }
}
