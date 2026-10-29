package com.cvcompass.backend.candidate.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvcompass.backend.identity.config.UserProvisioningProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class M11PropertiesTests {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PublicationProperties.class, UserProvisioningProperties.class})
    static class Binding {}

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void operationalOverridesBindWithIndependentConcurrencyAndPages() {
        context.withPropertyValues(
                        "cv.candidate.publication.page-size=101",
                        "cv.candidate.publication.concurrency=2",
                        "cv.candidate.publication.list-page-size=50",
                        "cv.candidate.publication.delay=2s")
                .run(
                        app -> {
                            assertThat(app).hasNotFailed();
                            var p = app.getBean(PublicationProperties.class);
                            assertThat(p.getPageSize()).isEqualTo(101);
                            assertThat(p.getConcurrency()).isEqualTo(2);
                            assertThat(p.getListPageSize()).isEqualTo(50);
                            assertThat(p.getDelay()).isEqualTo(java.time.Duration.ofSeconds(2));
                        });
    }

    @Test
    void invalidOperationsFailStartupEvenWhenPublicationDisabled() {
        for (String value :
                new String[] {
                    "page-size=0",
                    "page-size=1001",
                    "concurrency=0",
                    "concurrency=5",
                    "list-page-size=0",
                    "list-page-size=101",
                    "delay=0s",
                    "delay=2d"
                })
            context.withPropertyValues("cv.candidate.publication." + value)
                    .run(app -> assertThat(app).as(value).hasFailed());
    }

    @Test
    void provisioningRequiresPairedIdentityAndHashFileAndKnownRole() {
        for (String value :
                new String[] {
                    "username=missing-hash", "hash-file=/private/missing-user.hash", "role=ROOT"
                })
            context.withPropertyValues("cv.security.provision." + value)
                    .run(app -> assertThat(app).as(value).hasFailed());
    }
}
