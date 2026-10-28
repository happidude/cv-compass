package com.cvcompass.backend.candidate.controller;

import com.cvcompass.backend.candidate.service.CandidateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {
    private final com.cvcompass.backend.candidate.config.PublicationProperties properties;
    private final CandidateService service;

    public CandidateController(
            CandidateService service,
            com.cvcompass.backend.candidate.config.PublicationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    public record BasicEdit(
            @NotNull @PositiveOrZero Long version, JsonNode data, boolean confirm) {}

    public record ExperienceEdit(
            @NotNull @PositiveOrZero Long version,
            UUID experienceId,
            JsonNode data,
            @NotNull @Pattern(regexp = "SAVE|CONFIRM|DELETE") String operation) {}

    public record Decision(
            @NotNull @PositiveOrZero Long version,
            @NotNull @Pattern(regexp = "ACCEPT|EDIT|REJECT") String decision,
            UUID targetExperienceId,
            JsonNode data) {}

    public record DocumentLink(@NotNull @PositiveOrZero Long version, @Positive long documentId) {}

    @PostMapping("/{id}/documents")
    public CandidateService.Profile attach(
            @PathVariable long id, @Valid @RequestBody DocumentLink request, Principal actor) {
        return service.attach(id, request.version(), request.documentId(), actor.getName());
    }

    @GetMapping
    public CandidateService.Page list(
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return service.list(beforeId, size == null ? properties.getListPageSize() : size);
    }

    @GetMapping("/{id}")
    public CandidateService.Profile detail(@PathVariable long id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/proposals")
    public List<CandidateService.Proposal> proposals(
            @PathVariable long id, @RequestParam(required = false) Integer size) {
        return service.proposals(id, null, size == null ? properties.getListPageSize() : size);
    }

    @GetMapping("/{id}/history")
    public List<CandidateService.History> history(
            @PathVariable long id,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return service.history(id, beforeId, size == null ? properties.getListPageSize() : size);
    }

    @PutMapping("/{id}/basic")
    public CandidateService.Profile basic(
            @PathVariable long id, @Valid @RequestBody BasicEdit edit, Principal actor) {
        return service.basic(id, edit.version(), edit.data(), edit.confirm(), actor.getName());
    }

    @PostMapping("/{id}/experiences")
    public CandidateService.Profile experience(
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
    public CandidateService.Profile decide(
            @PathVariable long id,
            @PathVariable UUID proposalId,
            @Valid @RequestBody Decision edit,
            Principal actor) {
        return service.decide(
                id,
                edit.version(),
                proposalId,
                edit.decision(),
                edit.targetExperienceId(),
                edit.data(),
                actor.getName());
    }
}
