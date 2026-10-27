package com.cvcompass.backend.storage;

import java.io.InputStream;

/**
 * Generic object storage. No format validation, persistence, filenames, or HTTP concerns.
 * Implementations are selected by configuration and injected through this interface.
 */
public interface FileStorageService {
    /** Checks usable read/write/publication support without publishing a user object. */
    void verifyAvailable();

    /** Optional size hint; implementations may detect insufficient local capacity without reserving it. */
    default void verifyAvailable(long requiredBytes) {
        verifyAvailable();
    }

    /**
     * Reads from the current stream position through EOF, leaving the caller's stream open
     * on success and failure. Generates a new key; never replaces an existing object.
     * Returns only after complete publication. Failed writes clean up partial data where
     * feasible and throw StorageException, retaining cleanup failures as suppressed causes.
     */
    StoredFile store(InputStream content);

    /**
     * Opens a caller-owned stream; the caller must close it, preferably with try-with-resources.
     * Missing objects throw StorageException with NOT_FOUND. Open failures are StorageException;
     * subsequent stream I/O failures are IOException. No full-file buffering is required.
     */
    InputStream read(StorageKey key);

    /** Idempotent: absent objects are a successful no-op. Other failures throw StorageException. */
    void delete(StorageKey key);

    /** False only for an absent object; access/I/O errors throw StorageException. */
    boolean exists(StorageKey key);
}
