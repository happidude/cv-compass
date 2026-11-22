package com.cvcompass.backend.catalog.controller;

import com.cvcompass.backend.catalog.dto.CatalogSeed;
import com.cvcompass.backend.catalog.dto.ImportCounts;
import com.cvcompass.backend.catalog.service.CatalogImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogImportController {
    private final CatalogImportService service;

    public CatalogImportController(CatalogImportService service) {
        this.service = service;
    }

    @PostMapping("/import")
    public ImportCounts importSeed(@RequestBody(required = false) CatalogSeed seed) {
        return service.importSeed(seed);
    }
}
