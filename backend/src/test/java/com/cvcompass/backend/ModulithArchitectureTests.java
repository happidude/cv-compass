package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTests {
    @Test
    void businessAndHttpInfrastructureObeyModuleBoundaries() {
        var modules = ApplicationModules.of(BackendApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("ingestion", "web", "storage");
        modules.verify();
    }
}
