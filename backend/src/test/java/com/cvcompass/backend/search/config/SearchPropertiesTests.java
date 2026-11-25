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
                    assertThat(properties.getQuery().getResolveLimit()).isEqualTo(5);
                    assertThat(properties.getQuery().getKeywordTrigramThreshold())
                            .isEqualByComparingTo("0.5");
                    assertThat(properties.getQuery().getWeights().getTitleCatalogBonus())
                            .isEqualByComparingTo("30");
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
    void queryDefaultsAndValidOverridesBind() {
        context.withPropertyValues(
                        "cv.search.query.default-limit=5",
                        "cv.search.query.max-limit=10",
                        "cv.search.query.suggest-limit=3",
                        "cv.search.query.resolve-limit=2",
                        "cv.search.query.trigram-threshold=0.4",
                        "cv.search.query.keyword-trigram-threshold=0.7",
                        "cv.search.query.weights.title-catalog-bonus=25",
                        "cv.search.query.weights.company-current=5")
                .run(
                        app -> {
                            assertThat(app).hasNotFailed();
                            var query = app.getBean(SearchProperties.class).getQuery();
                            assertThat(query.getDefaultLimit()).isEqualTo(5);
                            assertThat(query.getMaxLimit()).isEqualTo(10);
                            assertThat(query.getSuggestLimit()).isEqualTo(3);
                            assertThat(query.getResolveLimit()).isEqualTo(2);
                            assertThat(query.getTrigramThreshold()).isEqualByComparingTo("0.4");
                            assertThat(query.getKeywordTrigramThreshold())
                                    .isEqualByComparingTo("0.7");
                            assertThat(query.getWeights().getTitleCatalogBonus())
                                    .isEqualByComparingTo("25");
                            assertThat(query.getWeights().getCompanyCurrent())
                                    .isEqualByComparingTo("5");
                        });
    }

    @Test
    void overlappingTiersAndInvalidQueryBoundsPreventStartup() {
        for (String property :
                new String[] {
                    "max-limit=51",
                    "max-limit=0",
                    "default-limit=51",
                    "default-limit=0",
                    "suggest-limit=0",
                    "suggest-limit=51",
                    "resolve-limit=0",
                    "resolve-limit=51",
                    "trigram-threshold=0",
                    "trigram-threshold=1.1",
                    "keyword-trigram-threshold=0",
                    "keyword-trigram-threshold=1.1",
                    "weights.title-catalog-bonus=-1",
                    "weights.title-catalog-bonus=100",
                    "weights.keyword-text=-1",
                    "weights.title-catalog=100",
                    "weights.company-parent=300",
                    "weights.company-direct=700",
                    "weights.company-current=200"
                }) {
            context.withPropertyValues("cv.search.query." + property)
                    .run(app -> assertThat(app).as(property).hasFailed());
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
