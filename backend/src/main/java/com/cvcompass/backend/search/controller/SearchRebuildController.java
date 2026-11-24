package com.cvcompass.backend.search.controller;

import com.cvcompass.backend.search.dto.RebuildResult;
import com.cvcompass.backend.search.service.SearchRebuildService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/search")
public class SearchRebuildController {
    private final SearchRebuildService service;

    public SearchRebuildController(SearchRebuildService service) {
        this.service = service;
    }

    @PostMapping("/rebuild")
    public RebuildResult rebuild() {
        return service.rebuildAll();
    }
}
