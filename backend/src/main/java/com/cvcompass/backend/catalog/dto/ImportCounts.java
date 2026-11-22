package com.cvcompass.backend.catalog.dto;

/** Counts catalog rows, including implicit parents and aliases. */
public record ImportCounts(int created, int updated, int unchanged) {}
