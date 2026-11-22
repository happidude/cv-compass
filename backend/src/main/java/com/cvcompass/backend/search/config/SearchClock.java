package com.cvcompass.backend.search.config;

import java.time.Clock;

/** Search-owned wrapper so the module never adds a second {@link Clock} bean to the context. */
public record SearchClock(Clock clock) {}
