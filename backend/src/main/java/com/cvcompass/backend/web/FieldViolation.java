package com.cvcompass.backend.web;

/** Validation metadata only; rejected request values must not be included. */
public record FieldViolation(String field, String message) {}
