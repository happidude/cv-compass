package com.cvcompass.backend.catalog.api;

import com.cvcompass.backend.catalog.Fold;

/** Read-only text folding shared with other modules; delegates to the catalog folding rules. */
public final class CatalogText {
    private CatalogText() {}

    public static String fold(String value) {
        return Fold.fold(value);
    }
}
