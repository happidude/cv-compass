package com.cvcompass.backend.mapping.controller;

import com.cvcompass.backend.mapping.dto.RemapRequest;
import com.cvcompass.backend.mapping.dto.RemapRun;
import com.cvcompass.backend.mapping.service.RemapService;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/mapping/remaps")
public class RemapController {
    private final RemapService service;

    public RemapController(RemapService service) {
        this.service = service;
    }

    @PostMapping
    public RemapRun create(
            @RequestBody(required = false) RemapRequest request, Principal principal) {
        return service.create(request, principal.getName());
    }

    @GetMapping
    public List<RemapRun> recent() {
        return service.recent();
    }

    @GetMapping("/{id}")
    public RemapRun get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/pause")
    public RemapRun pause(@PathVariable long id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    public RemapRun resume(@PathVariable long id) {
        return service.resume(id);
    }
}
