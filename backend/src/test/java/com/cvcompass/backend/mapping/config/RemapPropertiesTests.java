package com.cvcompass.backend.mapping.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RemapPropertiesTests {
    @Test
    void defaultsAreValidAndLimitsPreventUnboundedOrHighPriorityRemaps() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var properties = new RemapProperties();
            assertThat(validator.validate(properties)).isEmpty();
            properties.setPriority(0);
            properties.setBatchSize(10001);
            properties.setMaxPending(0);
            properties.setListLimit(0);
            properties.setDelay(Duration.ZERO);
            assertThat(validator.validate(properties)).hasSize(5);
            properties.setPriority(32768);
            assertThat(validator.validate(properties)).hasSize(5);
        }
    }
}
