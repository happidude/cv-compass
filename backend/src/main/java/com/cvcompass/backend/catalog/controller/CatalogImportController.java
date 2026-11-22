package com.cvcompass.backend.catalog.controller;

import com.cvcompass.backend.catalog.dto.CatalogSeed;
import com.cvcompass.backend.catalog.dto.ImportCounts;
import com.cvcompass.backend.catalog.exception.CatalogFailure;
import com.cvcompass.backend.catalog.service.CatalogImportService;
import com.cvcompass.backend.web.ApiError;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    public ImportCounts importSeed(
            @RequestBody(required = false) CatalogSeed seed, Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")))
            throw new AccessDeniedException("FORBIDDEN");
        return service.importSeed(seed);
    }

    @ExceptionHandler(CatalogFailure.class)
    public ResponseEntity<ApiError> invalid(CatalogFailure failure) {
        return ResponseEntity.status(422)
                .body(
                        new ApiError(
                                failure.getMessage(),
                                "Invalid catalog seed",
                                MDC.get("correlationId"),
                                failure.violations()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden() {
        return ResponseEntity.status(403)
                .body(
                        new ApiError(
                                "FORBIDDEN", "Access denied", MDC.get("correlationId"), List.of()));
    }
}
