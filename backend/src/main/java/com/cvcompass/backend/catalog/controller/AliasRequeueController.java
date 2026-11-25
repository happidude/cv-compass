package com.cvcompass.backend.catalog.controller;

import com.cvcompass.backend.catalog.service.AliasRequeueService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/catalog/aliases")
public class AliasRequeueController {
    private final AliasRequeueService service;

    public AliasRequeueController(AliasRequeueService service) {
        this.service = service;
    }

    public record RequeueResult(int requeued) {}

    @PostMapping("/requeue")
    public RequeueResult requeue(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String errorCode) {
        return new RequeueResult(service.requeue(kind, errorCode));
    }
}
