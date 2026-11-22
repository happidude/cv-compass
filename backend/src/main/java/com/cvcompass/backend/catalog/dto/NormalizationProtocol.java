package com.cvcompass.backend.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NormalizationProtocol {
    private NormalizationProtocol() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(long id, String kind, String raw, String contextCompany) {}

    public record Code(String code, String label) {}

    public record Claim(
            UUID token,
            Instant leaseExpiresAt,
            String taxonomyVersion,
            Map<String, List<Code>> taxonomy,
            List<Item> items) {}

    public record Rejection(long id, String code) {}

    public record Submission(int accepted, List<Rejection> rejected) {}
}
