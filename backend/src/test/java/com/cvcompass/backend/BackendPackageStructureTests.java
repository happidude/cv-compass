package com.cvcompass.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import java.lang.reflect.Modifier;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class BackendPackageStructureTests {
    private static final String INGESTION = "com.cvcompass.backend.ingestion";
    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(INGESTION);

    @Test
    void ingestionTypesResideInTheirResponsibilityPackages() {
        var packages =
                Set.of(
                        "controller",
                        "exception",
                        "service",
                        "dto",
                        "entity",
                        "repository",
                        "enums",
                        "config",
                        "upload",
                        "source");
        for (var type : CLASSES) {
            Class<?> actual = type.reflect();
            String pkg = actual.getPackageName();
            assertThat(packages).as(type.getName()).contains(pkg.substring(INGESTION.length() + 1));
            if (actual.isAnnotationPresent(RestController.class))
                assertPackage(actual, "controller");
            if (actual.isAnnotationPresent(RestControllerAdvice.class)
                    || RuntimeException.class.isAssignableFrom(actual))
                assertPackage(actual, "exception");
            if (actual.isAnnotationPresent(Service.class)) assertPackage(actual, "service");
            if (actual.isRecord()) assertPackage(actual, "dto");
            if (actual.isAnnotationPresent(Entity.class)
                    || actual.isAnnotationPresent(MappedSuperclass.class))
                assertPackage(actual, "entity");
            if (JpaRepository.class.isAssignableFrom(actual)) assertPackage(actual, "repository");
            if (actual.isAnnotationPresent(Configuration.class)) assertPackage(actual, "config");
        }
    }

    @Test
    void responsibilityPackagesHaveNoCyclesAndControllersDoNotAccessPersistence() {
        noClasses()
                .that()
                .resideInAPackage(INGESTION + ".service..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web.multipart..", "java.net.http..")
                .check(CLASSES);
        slices().matching(INGESTION + ".(*)..").should().beFreeOfCycles().check(CLASSES);
        noClasses()
                .that()
                .resideInAPackage(INGESTION + ".controller..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(INGESTION + ".entity..", INGESTION + ".repository..")
                .check(CLASSES);
    }

    @Test
    void implementationTypesStayInternalAndAdviceCoversControllerPackage() throws Exception {
        for (String name :
                Set.of(
                        "upload.PdfUploadStream",
                        "config.BatchMultipartConfiguration",
                        "exception.IngestionExceptionHandler")) {
            assertThat(Modifier.isPublic(Class.forName(INGESTION + "." + name).getModifiers()))
                    .as(name)
                    .isFalse();
        }
        var advice =
                Class.forName(INGESTION + ".exception.IngestionExceptionHandler")
                        .getAnnotation(RestControllerAdvice.class);
        assertThat(advice.basePackages()).containsExactly(INGESTION + ".controller");
    }

    private static void assertPackage(Class<?> type, String responsibility) {
        assertThat(type.getPackageName())
                .as(type.getName())
                .isEqualTo(INGESTION + "." + responsibility);
    }
}
