package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTests {
    @Test
    void businessAndHttpInfrastructureObeyModuleBoundaries() {
        var modules = ApplicationModules.of(BackendApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "ingestion",
                        "web",
                        "storage",
                        "mapping",
                        "candidate",
                        "identity",
                        "catalog",
                        "search");
        assertThat(
                        modules.getModuleByName("candidate")
                                .orElseThrow()
                                .getNamedInterfaces()
                                .getByName("catalog-links"))
                .isPresent();
        assertThat(
                        modules.getModuleByName("catalog")
                                .orElseThrow()
                                .getNamedInterfaces()
                                .getByName("lookup"))
                .isPresent();
        assertThat(
                        modules.getModuleByName("candidate")
                                .orElseThrow()
                                .getNamedInterfaces()
                                .getByName("events"))
                .isPresent();
        assertThat(
                        modules.getModuleByName("candidate")
                                .orElseThrow()
                                .getNamedInterfaces()
                                .getByName("metrics"))
                .isPresent();
        modules.verify();
    }
}
