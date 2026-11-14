package com.cvcompass.backend.mapping.api;

/** Publication capabilities derived from a validated mapping result. */
public record PublishableMapping(
        int schemaVersion,
        String engineVersion,
        boolean personalInfo,
        boolean canonicalPersonalValues) {}
