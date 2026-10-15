package com.cvcompass.backend.storage;

import java.util.regex.Pattern;

/** Opaque object identity, never a path or a user-supplied filename. Persist value(), not toString(). */
public record StorageKey(String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-f0-9]{32}");

    public StorageKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new StorageException(StorageException.Reason.INVALID_KEY, "Invalid storage key");
        }
    }
}
