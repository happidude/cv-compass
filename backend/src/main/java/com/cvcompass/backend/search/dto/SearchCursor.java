package com.cvcompass.backend.search.dto;

import com.cvcompass.backend.search.exception.SearchFailure;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record SearchCursor(BigDecimal score, long id) {
    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (score.toPlainString() + ":" + id).getBytes(StandardCharsets.US_ASCII));
    }

    public static SearchCursor decode(String encoded) {
        if (encoded == null) return null;
        try {
            String value =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            if (!value.matches("(?:0|[1-9][0-9]*)\\.[0-9]{6}:[1-9][0-9]*")) {
                throw new IllegalArgumentException();
            }
            String[] parts = value.split(":");
            var cursor = new SearchCursor(new BigDecimal(parts[0]), Long.parseLong(parts[1]));
            if (!cursor.encode().equals(encoded)) throw new IllegalArgumentException();
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw new SearchFailure("cursor", "Invalid cursor");
        }
    }
}
