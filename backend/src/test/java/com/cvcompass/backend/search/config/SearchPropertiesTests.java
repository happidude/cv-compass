package com.cvcompass.backend.search.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cvcompass.backend.search.service.SearchRebuildService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;

class SearchPropertiesTests {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(SearchConfiguration.class);

    @Test
    void defaultsPreserveTheExistingBatchSizeAndNightlySchedule() {
        context.run(
                app -> {
                    assertThat(app).hasNotFailed();
                    var properties = app.getBean(SearchProperties.class);
                    assertThat(properties.getRebuildBatchSize()).isEqualTo(200);
                    assertThat(properties.getNightlyCron()).isEqualTo("0 30 2 * * *");
                });
    }

    @Test
    void batchSizeAcceptsBothBoundariesAndRejectsValuesOutsideThem() {
        for (int size : new int[] {1, 1000}) {
            context.withPropertyValues("cv.search.rebuild-batch-size=" + size)
                    .run(
                            app -> {
                                assertThat(app).hasNotFailed();
                                var properties = app.getBean(SearchProperties.class);
                                assertThat(properties.getRebuildBatchSize()).isEqualTo(size);
                            });
        }
        for (int size : new int[] {0, 1001}) {
            context.withPropertyValues("cv.search.rebuild-batch-size=" + size)
                    .run(app -> assertThat(app).as("batch size %s", size).hasFailed());
        }
    }

    @Test
    void blankNightlyCronFailsBinding() {
        for (String cron : new String[] {"", "   "}) {
            context.withPropertyValues("cv.search.nightly-cron=" + cron)
                    .run(app -> assertThat(app).hasFailed());
        }
    }

    @Test
    void configuredCronIsUsedByTheRegisteredNightlyTask() {
        String cron = "0 15 4 * * *";
        context.withPropertyValues("cv.search.nightly-cron=" + cron)
                .withBean(
                        SearchNightlyJob.class,
                        () -> new SearchNightlyJob(mock(SearchRebuildService.class)))
                .run(
                        app -> {
                            assertThat(app).hasNotFailed();
                            assertThat(app.getBean(SearchProperties.class).getNightlyCron())
                                    .isEqualTo(cron);
                            assertThat(
                                            app.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                                    .getScheduledTasks())
                                    .extracting(task -> ((CronTask) task.getTask()).getExpression())
                                    .containsExactly(cron);
                        });
    }

    @Test
    void disabledCronDoesNotRegisterANightlyTask() {
        context.withPropertyValues("cv.search.nightly-cron=-")
                .withBean(
                        SearchNightlyJob.class,
                        () -> new SearchNightlyJob(mock(SearchRebuildService.class)))
                .run(
                        app -> {
                            assertThat(app).hasNotFailed();
                            assertThat(
                                            app.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                                    .getScheduledTasks())
                                    .isEmpty();
                        });
    }
}
