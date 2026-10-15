package com.cvcompass.backend;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import static org.assertj.core.api.Assertions.assertThat;

class ModulithArchitectureTests {
    @Test
    void businessAndHttpInfrastructureObeyModuleBoundaries() {
        var modules = ApplicationModules.of(BackendApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("ingestion", "web");
        modules.verify();
    }
}
