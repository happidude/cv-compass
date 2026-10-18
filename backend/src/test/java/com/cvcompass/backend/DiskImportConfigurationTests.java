package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.config.*;
import com.cvcompass.backend.ingestion.source.ImportSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class DiskImportConfigurationTests {
    @TempDir Path directory;
    ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(DiskImportConfiguration.class);
    @Test void disabledByDefaultWithoutRequiringInboxOrImportBeans() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ImportSource.class).doesNotHaveBean(DiskImportProperties.class));
    }
    ApplicationContextRunner enabled() throws Exception {
        Path inbox=Files.createDirectories(directory.resolve("inbox"));
        return runner.withPropertyValues("cv.import.enabled=true","cv.import.provider=local",
                "cv.import.source-directory="+inbox,"cv.storage.local.root-directory="+directory.resolve("storage"));
    }
    @Test void validLocalConfigurationUsesConditionalAdapterAndReleasesLock() throws Exception {
        enabled().run(context -> assertThat(context).hasNotFailed().hasSingleBean(ImportSource.class));
        enabled().run(context -> assertThat(context).hasNotFailed().hasSingleBean(ImportSource.class));
    }
    @Test void invalidProviderLimitsAndDurationsFailStartup() throws Exception {
        for(String property:java.util.List.of("cv.import.provider=unknown","cv.import.batch-size=11","cv.import.max-attempts=0",
                "cv.import.scan-interval=0s","cv.import.retry-backoff=-1s","cv.import.stability-interval=0s","cv.batch.max-files=1")) {
            enabled().withPropertyValues(property).run(context -> assertThat(context).as(property).hasFailed());
        }
    }
    @Test void enabledWithoutSourceFailsStartup() {
        runner.withPropertyValues("cv.import.enabled=true","cv.storage.local.root-directory="+directory.resolve("storage"))
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void secondContextCannotOwnTheSameInbox() throws Exception {
        var configured=enabled();
        configured.run(first -> {
            assertThat(first).hasNotFailed();
            configured.run(second -> assertThat(second).hasFailed());
        });
    }
}
