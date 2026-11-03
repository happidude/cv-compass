package com.cvcompass.backend.mapping.service;

import java.text.Normalizer;
import java.util.Locale;

final class PersonalInfoValidation {
    record Checked(String normalized, String issue) {}

    // Contact proximity cannot establish that an occupational heading is a person's name.
    private static final java.util.regex.Pattern ROLE_OR_DOCUMENT_HEADING =
            java.util.regex.Pattern.compile(
                    "\\b(?:engineer|developer|manager|designer|architect|analyst|consultant|specialist|director|intern|officer|recruiter|owner|lead|leader|head|executive|coordinator|administrator|accountant|auditor|scientist|technician|operator|supervisor|assistant|student|graduate|curriculum|vitae|resume|kỹ sư|chuyên viên|giám đốc|nhân viên|chuyên gia|trưởng phòng|sinh viên)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                            | java.util.regex.Pattern.UNICODE_CASE);

    static String name(String raw) {
        return java.util.regex.Pattern.compile(
                        "^(?:(?:Mr|Ms|Mrs)\\.?|Ông|Bà|Anh|Chị)\\s+",
                        java.util.regex.Pattern.CANON_EQ
                                | java.util.regex.Pattern.CASE_INSENSITIVE
                                | java.util.regex.Pattern.UNICODE_CASE)
                .matcher(raw)
                .replaceFirst("");
    }

    static Checked check(String field, String raw) {
        if (field.equals("email") && raw.matches("[^\\s]+[@.]\\n[^\\s@]+"))
            raw = raw.replace("\n", "");
        String text =
                Normalizer.normalize(raw, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        int limit =
                switch (field) {
                    case "fullName" -> 255;
                    case "email" -> 255;
                    case "phone" -> 50;
                    case "location" -> 255;
                    default -> 10000;
                };
        if (text.isBlank() || text.codePointCount(0, text.length()) > limit)
            return new Checked(null, "VALUE_LENGTH_INVALID");
        return switch (field) {
            case "gender" ->
                    switch (text.toLowerCase(Locale.ROOT)) {
                        case "nam", "male", "m" -> new Checked("MALE", null);
                        case "nữ", "female", "f" -> new Checked("FEMALE", null);
                        default -> new Checked(null, "UNRECOGNIZED_GENDER");
                    };
            case "email" -> {
                if (!text.matches("[^\\s@<>]+@[^\\s@<>.]+(?:\\.[^\\s@<>.]+)+")
                        || text.contains("..")) yield new Checked(null, "INVALID_EMAIL");
                int at = text.lastIndexOf('@');
                yield new Checked(
                        text.substring(0, at + 1) + text.substring(at + 1).toLowerCase(Locale.ROOT),
                        null);
            }
            case "phone" -> {
                if (!text.matches("\\+?[0-9(). /-]+(?:\\s*(?i:ext\\.?|x|máy lẻ)\\s*[0-9]{1,8})?")
                        || text.replaceAll("\\s+", "")
                                .matches(
                                        "(?:[12][0-9]{3}[-/.][01]?[0-9][-/.][0-3]?[0-9]|[0-3]?[0-9][-/.][01]?[0-9][-/.][12][0-9]{3}|[12][0-9]{3}[-/.–—][12][0-9]{3})"))
                    yield new Checked(null, "INVALID_PHONE");
                String normalized =
                        text.replaceFirst("(?iu)\\s*(?:ext\\.?|x|máy lẻ)\\s*", "x")
                                .replaceAll("[(). /-]", "");
                String digits = normalized.split("x", 2)[0].replace("+", "");
                yield digits.length() >= 7 && digits.length() <= 15
                        ? new Checked(normalized, null)
                        : new Checked(null, "INVALID_PHONE");
            }
            case "fullName" -> {
                String cleaned = name(text);
                if (!cleaned.matches("[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*")
                        || cleaned.split(" ").length < 2
                        || ROLE_OR_DOCUMENT_HEADING.matcher(cleaned).find())
                    yield new Checked(null, "INVALID_NAME");
                yield new Checked(cleaned, null);
            }
            default -> new Checked(text, null);
        };
    }
}
