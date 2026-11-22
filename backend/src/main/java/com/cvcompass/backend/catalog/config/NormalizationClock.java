package com.cvcompass.backend.catalog.config;

import java.time.Clock;

/** Module-owned wrapper avoids registering another framework Clock bean. */
public record NormalizationClock(Clock clock) {}
