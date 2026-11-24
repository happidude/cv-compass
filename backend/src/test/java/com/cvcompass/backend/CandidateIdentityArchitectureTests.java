package com.cvcompass.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class CandidateIdentityArchitectureTests {
    private static final String BASE = "com.cvcompass.backend.";
    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(BASE + "candidate", BASE + "identity");

    @Test
    void typesAndDtosResideInTheirResponsibilityPackages() {
        for (var type : CLASSES) {
            Class<?> actual = type.reflect();
            if (type.getSimpleName().equals("package-info")) {
                var namedInterfaces =
                        Map.of(
                                BASE + "candidate.catalogapi", "catalog-links",
                                BASE + "candidate.events", "events",
                                BASE + "candidate.metrics", "metrics");
                if (namedInterfaces.containsKey(actual.getPackageName())) {
                    assertThat(
                                    actual.getAnnotation(
                                                    org.springframework.modulith.NamedInterface
                                                            .class)
                                            .value())
                            .containsExactly(namedInterfaces.get(actual.getPackageName()));
                    continue;
                }
                assertThat(
                                actual.isAnnotationPresent(
                                        org.springframework.modulith.ApplicationModule.class))
                        .as(type.getName())
                        .isTrue();
                continue;
            }
            String pkg = actual.getPackageName();
            String layer = pkg.substring(pkg.lastIndexOf('.') + 1);
            if (layer.equals("catalogapi"))
                assertThat(pkg).as(type.getName()).isEqualTo(BASE + "candidate.catalogapi");
            if (layer.equals("mapping"))
                assertThat(pkg).as(type.getName()).isEqualTo(BASE + "candidate.mapping");
            else
                assertThat(
                                Set.of(
                                        "controller",
                                        "service",
                                        "repository",
                                        "dto",
                                        "config",
                                        "exception",
                                        "mapper",
                                        "catalogapi",
                                        "events",
                                        "metrics"))
                        .as(type.getName())
                        .contains(layer);
            if (actual.isRecord())
                assertLayer(
                        actual,
                        Set.of("catalogapi", "events", "metrics").contains(layer) ? layer : "dto");
            if (actual.getSimpleName().endsWith("Mapper"))
                assertLayer(actual, pkg.equals(BASE + "candidate.mapping") ? "mapping" : "mapper");
            if (actual.isAnnotationPresent(RestController.class)) assertLayer(actual, "controller");
            if (actual.isAnnotationPresent(RestControllerAdvice.class)
                    || RuntimeException.class.isAssignableFrom(actual))
                assertLayer(actual, "exception");
            if (actual.isAnnotationPresent(Service.class)) assertLayer(actual, "service");
            if (actual.isAnnotationPresent(Repository.class)) assertLayer(actual, "repository");
            if (actual.isAnnotationPresent(Configuration.class)
                    || actual.isAnnotationPresent(ConfigurationProperties.class))
                assertLayer(actual, "config");
        }
    }

    @Test
    void servicesAndControllersDoNotUseJdbcAndControllersDoNotAccessPersistence() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "..candidate.service..",
                        "..identity.service..",
                        "..candidate.controller..",
                        "..identity.controller..",
                        "..candidate.mapper..",
                        "..candidate.catalogapi..",
                        BASE + "candidate.mapping",
                        "..identity.mapper..",
                        "..identity.config..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.jdbc..",
                        "java.sql..",
                        "javax.sql..",
                        "jakarta.persistence..")
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAnyPackage("..candidate.controller..", "..identity.controller..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..repository..", "..entity..")
                .check(CLASSES);
    }

    @Test
    void repositoriesAndDtosDoNotDependOnApplicationOrHttpLayers() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "..candidate.mapper..", BASE + "candidate.mapping", "..identity.mapper..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..service..", "..repository..", "..controller..", "..config..")
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAnyPackage(
                        "..candidate.repository..",
                        "..identity.repository..",
                        "..candidate.dto..",
                        "..identity.dto..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..candidate.service..",
                        "..identity.service..",
                        "..controller..",
                        "..candidate.config..",
                        "..identity.config..")
                .check(CLASSES);
        for (String module : Set.of("candidate", "identity")) {
            slices().matching(BASE + module + ".(*)..").should().beFreeOfCycles().check(CLASSES);
        }
        noClasses()
                .that()
                .resideInAnyPackage("..candidate..", "..identity..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..ingestion.repository..", "..mapping.repository..")
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAnyPackage("..candidate.catalogapi..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..candidate.repository..",
                        "..candidate.service..",
                        "..candidate.controller..")
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAnyPackage("..candidate..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..catalog..")
                .check(CLASSES);
    }

    @Test
    void m11ModuleBridgesKeepJdbcInsideTheirRepositories() {
        var bridges =
                new ClassFileImporter()
                        .importClasses(
                                com.cvcompass.backend.ingestion.service.CandidateDocumentBridge
                                        .class,
                                com.cvcompass.backend.ingestion.service.OriginalPdfService.class,
                                com.cvcompass.backend.mapping.api.CompletedMappings.class);
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .check(bridges);
    }

    private static void assertLayer(Class<?> type, String layer) {
        assertThat(type.getPackageName()).as(type.getName()).endsWith("." + layer);
    }
}
