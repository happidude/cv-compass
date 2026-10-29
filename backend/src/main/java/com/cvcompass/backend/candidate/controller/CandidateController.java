package com.cvcompass.backend.candidate.controller;

import com.cvcompass.backend.candidate.config.PublicationProperties;
import com.cvcompass.backend.candidate.dto.BasicEdit;
import com.cvcompass.backend.candidate.dto.Decision;
import com.cvcompass.backend.candidate.dto.DocumentLink;
import com.cvcompass.backend.candidate.dto.ExperienceEdit;
import com.cvcompass.backend.candidate.dto.History;
import com.cvcompass.backend.candidate.dto.Page;
import com.cvcompass.backend.candidate.dto.Profile;
import com.cvcompass.backend.candidate.dto.Proposal;
import com.cvcompass.backend.candidate.service.CandidateQueryService;
import com.cvcompass.backend.candidate.service.CandidateReviewService;
import com.cvcompass.backend.candidate.service.CandidateRevisionService;
import com.cvcompass.backend.candidate.service.CandidateService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {
    private final PublicationProperties properties;
    private final CandidateService service;
    private final CandidateQueryService queries;
    private final CandidateReviewService reviews;
    private final CandidateRevisionService revisions;

    public CandidateController(
            CandidateService service,
            CandidateQueryService queries,
            CandidateReviewService reviews,
            CandidateRevisionService revisions,
            PublicationProperties properties) {
        this.service = service;
        this.queries = queries;
        this.reviews = reviews;
        this.revisions = revisions;
        this.properties = properties;
    }

    @PostMapping("/{id}/documents")
    public Profile attach(
            @PathVariable long id, @Valid @RequestBody DocumentLink request, Principal actor) {
        return service.attach(id, request.version(), request.documentId(), actor.getName());
    }

    @GetMapping
    public Page list(
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return queries.list(beforeId, size == null ? properties.getListPageSize() : size);
    }

    @GetMapping("/{id}")
    public Profile detail(@PathVariable long id) {
        return queries.detail(id);
    }

    @GetMapping("/{id}/proposals")
    public List<Proposal> proposals(
            @PathVariable long id, @RequestParam(required = false) Integer size) {
        return queries.proposals(id, null, size == null ? properties.getListPageSize() : size);
    }

    @GetMapping("/{id}/history")
    public List<History> history(
            @PathVariable long id,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return revisions.history(id, beforeId, size == null ? properties.getListPageSize() : size);
    }

    @PutMapping("/{id}/basic")
    public Profile basic(
            @PathVariable long id, @Valid @RequestBody BasicEdit edit, Principal actor) {
        return service.basic(id, edit.version(), edit.data(), edit.confirm(), actor.getName());
    }

    @PostMapping("/{id}/experiences")
    public Profile experience(
            @PathVariable long id, @Valid @RequestBody ExperienceEdit edit, Principal actor) {
        return service.editExperience(
                id,
                edit.version(),
                edit.experienceId(),
                edit.data(),
                edit.operation(),
                actor.getName());
    }

    @PostMapping("/{id}/proposals/{proposalId}")
    public Profile decide(
            @PathVariable long id,
            @PathVariable UUID proposalId,
            @Valid @RequestBody Decision edit,
            Principal actor) {
        return reviews.decide(
                id,
                edit.version(),
                proposalId,
                edit.decision(),
                edit.targetExperienceId(),
                edit.data(),
                actor.getName());
    }
}
