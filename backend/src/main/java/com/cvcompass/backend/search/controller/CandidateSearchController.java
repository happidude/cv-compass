package com.cvcompass.backend.search.controller;

import com.cvcompass.backend.search.dto.CandidateSearchPage;
import com.cvcompass.backend.search.dto.CandidateSearchPage.CompanySuggestion;
import com.cvcompass.backend.search.dto.CandidateSearchRequest;
import com.cvcompass.backend.search.service.CandidateSearchService;
import java.util.List;
import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/candidates/search")
public class CandidateSearchController {
    private final CandidateSearchService service;

    public CandidateSearchController(CandidateSearchService service) {
        this.service = service;
    }

    @GetMapping
    public CandidateSearchPage search(@RequestParam MultiValueMap<String, String> parameters) {
        return service.search(CandidateSearchRequest.from(parameters));
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return service.options();
    }

    @GetMapping("/companies")
    public List<CompanySuggestion> companies(@RequestParam(required = false) String q) {
        return service.companies(q);
    }
}
