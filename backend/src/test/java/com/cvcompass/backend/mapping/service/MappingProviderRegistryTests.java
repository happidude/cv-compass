package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.mapping.config.MappingProperties;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class MappingProviderRegistryTests {
    @Test
    void serviceRegistersExactlyTheEightInProcessVersions() throws ReflectiveOperationException {
        var service =
                new MappingService(
                        mock(MappingRepository.class),
                        mock(OcrExtractionReader.class),
                        mock(MappingEngine.class),
                        mock(ObjectMapper.class),
                        mock(PlatformTransactionManager.class),
                        new MappingProperties(),
                        mock(ExperienceEngine.class),
                        mock(PersonalInfoEngine.class),
                        mock(PersonalInfoEngineV31.class),
                        mock(PersonalInfoEngineV32.class),
                        mock(PersonalInfoEngineV33.class),
                        mock(PersonalInfoEngineV34.class),
                        mock(PersonalInfoEngineV35.class));
        var field = MappingService.class.getDeclaredField("registry");
        field.setAccessible(true);
        var registry = (MappingProviderRegistry) field.get(service);

        assertThat(registry.versions())
                .containsExactlyInAnyOrder(
                        MappingEngine.VERSION,
                        ExperienceEngine.VERSION,
                        PersonalInfoEngine.VERSION,
                        PersonalInfoEngineV31.VERSION,
                        PersonalInfoEngineV32.VERSION,
                        PersonalInfoEngineV33.VERSION,
                        PersonalInfoEngineV34.VERSION,
                        PersonalInfoEngineV35.VERSION);
        for (var version : registry.versions()) {
            var provider = registry.require(version);
            assertThat(provider.engineVersion()).isEqualTo(version);
            assertThat(provider.kind()).isEqualTo(MappingProviderKind.IN_PROCESS);
        }
        assertThatThrownBy(() -> registry.versions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unknownAndNullVersionsHaveTheOriginalErrorMessage() {
        var registry =
                new MappingProviderRegistry(
                        List.of(new InProcessMappingProvider("synthetic", (claim, input) -> null)));

        for (var version : Arrays.asList("unknown", null)) {
            assertThatThrownBy(() -> registry.require(version))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unsupported mapping engine");
        }
    }

    @Test
    void duplicateVersionsFailAtConstruction() {
        var first = new InProcessMappingProvider("synthetic", (claim, input) -> null);
        var second = new InProcessMappingProvider("synthetic", (claim, input) -> null);

        assertThatThrownBy(() -> new MappingProviderRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullRegistrationsFailAtConstruction() {
        assertThatThrownBy(() -> new MappingProviderRegistry(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MappingProviderRegistry(Arrays.asList((MappingProvider) null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new MappingProviderRegistry(
                                        List.of(
                                                new InProcessMappingProvider(
                                                        null, (claim, input) -> null))))
                .isInstanceOf(IllegalStateException.class);
    }
}
