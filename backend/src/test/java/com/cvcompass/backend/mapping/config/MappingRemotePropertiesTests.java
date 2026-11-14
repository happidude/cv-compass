package com.cvcompass.backend.mapping.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MappingRemotePropertiesTests {
    @Test
    void defaultsAreDisabledAndTimingRejectsInvalidRelationships() {
        var properties = new MappingRemoteProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isTimingValid()).isTrue();
        assertThat(properties.getWorkers()).isEmpty();
        properties.setHeartbeatInterval(Duration.ofSeconds(60));
        assertThat(properties.isTimingValid()).isFalse();
        properties.setHeartbeatInterval(Duration.ofSeconds(30));
        properties.setHardDeadline(Duration.ofSeconds(120));
        assertThat(properties.isTimingValid()).isFalse();
    }

    @Test
    void hashesCannotBeSharedBetweenWorkers() {
        var properties = new MappingRemoteProperties();
        var worker = new MappingRemoteProperties.Worker();
        worker.setCredentialSha256(Set.of("a".repeat(64)));
        properties.setWorkers(Map.of("synthetic-one", worker, "synthetic-two", worker));
        assertThat(properties.isWorkersValid()).isFalse();
    }
}
