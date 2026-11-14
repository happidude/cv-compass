package com.cvcompass.backend.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Extraction;
import com.cvcompass.backend.mapping.config.MappingProperties;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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
    void unknownVersionsHaveTheOriginalErrorMessage() {
        var registry =
                new MappingProviderRegistry(
                        List.of(new InProcessMappingProvider("synthetic", (claim, input) -> null)));

        assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported mapping engine");
    }

    @Test
    void nullVersionThrowsNullPointerExceptionLikeTheOriginalSwitch() {
        var registry = new MappingProviderRegistry(List.of());

        assertThatThrownBy(() -> registry.require(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void eachVersionCallsOnlyItsCorrespondingEngineOnce() throws ReflectiveOperationException {
        var engine = mock(MappingEngine.class);
        var experience = mock(ExperienceEngine.class);
        var personal = mock(PersonalInfoEngine.class);
        var grammarV31 = mock(PersonalInfoEngineV31.class);
        var grammarV32 = mock(PersonalInfoEngineV32.class);
        var grammarV33 = mock(PersonalInfoEngineV33.class);
        var grammarV34 = mock(PersonalInfoEngineV34.class);
        var grammarV35 = mock(PersonalInfoEngineV35.class);
        var service =
                new MappingService(
                        mock(MappingRepository.class),
                        mock(OcrExtractionReader.class),
                        engine,
                        mock(ObjectMapper.class),
                        mock(PlatformTransactionManager.class),
                        new MappingProperties(),
                        experience,
                        personal,
                        grammarV31,
                        grammarV32,
                        grammarV33,
                        grammarV34,
                        grammarV35);
        var field = MappingService.class.getDeclaredField("registry");
        field.setAccessible(true);
        var registry = (MappingProviderRegistry) field.get(service);
        var versions =
                List.of(
                        MappingEngine.VERSION,
                        ExperienceEngine.VERSION,
                        PersonalInfoEngine.VERSION,
                        PersonalInfoEngineV31.VERSION,
                        PersonalInfoEngineV32.VERSION,
                        PersonalInfoEngineV33.VERSION,
                        PersonalInfoEngineV34.VERSION,
                        PersonalInfoEngineV35.VERSION);
        var input = new Extraction(2, 4, "synthetic", "success", false, List.of());
        List<Consumer<MappingClaim>> verifications =
                List.of(
                        claim -> verify(engine).map(same(claim), same(input)),
                        claim -> verify(experience).map(same(claim), same(input)),
                        claim -> verify(personal).map(same(claim), same(input)),
                        claim -> verify(grammarV31).map(same(claim), same(input)),
                        claim -> verify(grammarV32).map(same(claim), same(input)),
                        claim -> verify(grammarV33).map(same(claim), same(input)),
                        claim -> verify(grammarV34).map(same(claim), same(input)),
                        claim -> verify(grammarV35).map(same(claim), same(input)));

        for (int i = 0; i < versions.size(); i++) {
            var version = versions.get(i);
            var claim =
                    new MappingClaim(
                            1, 2, 3, "synthetic", version, 1, UUID.randomUUID(), Instant.MAX, null);

            registry.require(version).map(claim, input);

            verifications.get(i).accept(claim);
            verifyNoMoreInteractions(
                    engine,
                    experience,
                    personal,
                    grammarV31,
                    grammarV32,
                    grammarV33,
                    grammarV34,
                    grammarV35);
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
