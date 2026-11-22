package com.cvcompass.backend.search.config;

import com.cvcompass.backend.search.service.SearchRebuildService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SearchNightlyJob {
    private final SearchRebuildService service;

    public SearchNightlyJob(SearchRebuildService service) {
        this.service = service;
    }

    @Scheduled(cron = "${cv.search.rebuild.cron:0 30 2 * * *}")
    public void rebuild() {
        service.rebuildOngoing();
    }
}
