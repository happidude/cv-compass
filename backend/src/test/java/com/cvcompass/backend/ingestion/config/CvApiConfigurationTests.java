package com.cvcompass.backend.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CvApiConfigurationTests {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            CvApiConfiguration.class, BatchMultipartConfiguration.class);

    @Test
    void defaultsAndOverridesBindToValidatedTypedProperties() {
        context.withPropertyValues(
                        "cv.upload.max-file-bytes=1024",
                        "cv.batch.max-files=2",
                        "cv.batch.max-file-bytes=512",
                        "cv.batch.multipart-overhead-bytes=100",
                        "cv.pagination.default-page-size=2",
                        "cv.pagination.max-page-size=3")
                .run(
                        app -> {
                            assertThat(app).hasNotFailed();
                            assertThat(app.getBean(UploadProperties.class).getMaxFileBytes())
                                    .isEqualTo(1024);
                            assertThat(app.getBean(BatchProperties.class).getMaxFiles())
                                    .isEqualTo(2);
                            var multipart =
                                    app.getBean(jakarta.servlet.MultipartConfigElement.class);
                            assertThat(multipart.getMaxRequestSize()).isEqualTo(1124);
                            assertThat(app.getBean(PaginationProperties.class).getDefaultPageSize())
                                    .isEqualTo(2);
                        });
    }

    @Test
    void invalidLimitsFailStartupInsteadOfSilentlyFallingBack() {
        for (String value :
                new String[] {
                    "cv.upload.max-file-bytes=0",
                    "cv.upload.max-file-bytes=10485761",
                    "cv.batch.max-files=11",
                    "cv.batch.max-file-bytes=-1",
                    "cv.batch.multipart-overhead-bytes=0",
                    "cv.pagination.default-page-size=101",
                    "cv.pagination.max-page-size=0"
                }) {
            context.withPropertyValues(value).run(app -> assertThat(app).hasFailed());
        }
    }
}
