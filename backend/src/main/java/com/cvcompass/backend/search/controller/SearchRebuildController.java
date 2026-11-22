package com.cvcompass.backend.search.controller;

import com.cvcompass.backend.search.dto.RebuildResult;
import com.cvcompass.backend.search.service.SearchRebuildService;
import com.cvcompass.backend.web.ApiError;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    public RebuildResult rebuild(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")))
            throw new AccessDeniedException("FORBIDDEN");
        return service.rebuildAll();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden() {
        return ResponseEntity.status(403)
                .body(
                        new ApiError(
                                "FORBIDDEN", "Access denied", MDC.get("correlationId"), List.of()));
    }
}
