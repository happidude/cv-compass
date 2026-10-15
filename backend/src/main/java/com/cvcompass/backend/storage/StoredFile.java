package com.cvcompass.backend.storage;

/** Actual byte count and lowercase SHA-256 of the content successfully published by store(). */
public record StoredFile(StorageKey key, long sizeBytes, String sha256) {
}
