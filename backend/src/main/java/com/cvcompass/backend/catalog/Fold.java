package com.cvcompass.backend.catalog;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Fold {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private Fold() {}

    public static String fold(String value) {
        if (value == null) return null;
        String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
        String stripped =
                MARKS.matcher(Normalizer.normalize(nfc, Normalizer.Form.NFD)).replaceAll("");
        return WHITESPACE
                .matcher(stripped.replace('đ', 'd').replace('Đ', 'd').toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .trim();
    }
}
