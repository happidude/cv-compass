package com.cvcompass.backend.mapping.dto;

import java.time.Instant;
import java.util.UUID;

public record MappingClaim(
        long jobId,
        long extractionId,
        long configId,
        String configRevision,
        String engineVersion,
        int attempt,
        UUID token,
        Instant leaseExpiresAt,
        MappingConfig definition) {}
