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

    // SpEL keeps one default in SearchProperties; SearchPropertiesTests verifies scheduling.
    @Scheduled(
            cron =
                    "#{@'cv.search-com.cvcompass.backend.search.config.SearchProperties'.nightlyCron}")
    public void rebuild() {
        service.rebuildOngoing();
    }
}
