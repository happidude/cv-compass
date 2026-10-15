package com.cvcompass.backend.storage;

import com.cvcompass.backend.storage.config.StorageConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigurationTests {
    @TempDir Path directory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfiguration.class);

    @Test
    void localProviderSelectsExactlyOneUsableInterfaceBean() {
        runner.withPropertyValues("cv.storage.provider=local",
                "cv.storage.local.root-directory=" + directory.resolve("objects")).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FileStorageService.class);
            var storage = context.getBean(FileStorageService.class);
            var stored = storage.store(java.io.InputStream.nullInputStream());
            assertThat(storage.exists(stored.key())).isTrue();
        });
    }

    @Test
    void unsupportedProviderFailsStartup() {
        runner.withPropertyValues("cv.storage.provider=s3").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void missingProviderFailsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankProviderFailsStartup() {
        runner.withPropertyValues("cv.storage.provider=").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingRootFailsStartup() {
        runner.withPropertyValues("cv.storage.provider=local").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void blankRootFailsStartup() {
        runner.withPropertyValues("cv.storage.provider=local", "cv.storage.local.root-directory=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void relativeRootFailsStartup() {
        runner.withPropertyValues("cv.storage.provider=local", "cv.storage.local.root-directory=relative")
                .run(context -> assertThat(context).hasFailed());
    }
}
