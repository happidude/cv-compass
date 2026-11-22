package com.cvcompass.backend.mapping.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

final class PersonalInfoValidation {
    record Checked(String normalized, String issue, String info) {
        Checked(String normalized, String issue) {
            this(normalized, issue, null);
        }
    }

    // Contact proximity cannot establish that an occupational heading is a person's name.
    private static final java.util.regex.Pattern ROLE_OR_DOCUMENT_HEADING =
            java.util.regex.Pattern.compile(
                    "\\b(?:engineer|developer|manager|designer|architect|analyst|consultant|specialist|director|intern|officer|recruiter|owner|lead|leader|head|executive|coordinator|administrator|accountant|auditor|scientist|technician|operator|supervisor|assistant|student|graduate|curriculum|vitae|resume|kỹ sư|chuyên viên|giám đốc|nhân viên|chuyên gia|trưởng phòng|sinh viên)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                            | java.util.regex.Pattern.UNICODE_CASE);

    private static final java.util.regex.Pattern REMOTE_HONORIFIC =
            java.util.regex.Pattern.compile(
                    "^(?:(?:Mr|Ms|Mrs|Miss|Mx|Dr)\\.?|Ông|Bà|Anh|Chị)\\s+",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                            | java.util.regex.Pattern.UNICODE_CASE
                            | java.util.regex.Pattern.CANON_EQ);

    private static final java.util.regex.Pattern REMOTE_FULL_NAME =
            java.util.regex.Pattern.compile(
                    "([\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*?)(?: *\\(([\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*)\\))?");

    private static final java.util.regex.Pattern REMOTE_PHONE_LABEL =
            java.util.regex.Pattern.compile(
                    "^(?:Tel|Phone|Mobile|ĐT|SĐT|Điện thoại|Hotline)\\b *[.:]? *",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                            | java.util.regex.Pattern.UNICODE_CASE);

    private static final java.util.regex.Pattern REMOTE_PHONE_DATE =
            java.util.regex.Pattern.compile(
                    "(?:[12][0-9]{3}[-/.][01]?[0-9][-/.][0-3]?[0-9]|[0-3]?[0-9][-/.][01]?[0-9][-/.][12][0-9]{3}|[12][0-9]{3}[-/.–—][12][0-9]{3})");

    private static final java.util.regex.Pattern REMOTE_PHONE_EXTENSION =
            java.util.regex.Pattern.compile("(?iu) *(?:ext\\.?|x|máy lẻ) *([0-9]{1,8})$");

    static Checked checkRemote(String field, String raw) {
        if (field.equals("phone")) return remotePhone(raw);
        if (!field.equals("fullName")) return check(field, raw);
        String text =
                Normalizer.normalize(raw, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        if (text.isBlank() || text.codePointCount(0, text.length()) > 255)
            return new Checked(null, "VALUE_LENGTH_INVALID");
        var honorific = REMOTE_HONORIFIC.matcher(text);
        boolean hasHonorific = honorific.find();
        String cleaned = hasHonorific ? text.substring(honorific.end()) : text;
        var parts = REMOTE_FULL_NAME.matcher(cleaned);
        if (!parts.matches()) return new Checked(null, "INVALID_NAME");
        String fullName = parts.group(1).strip();
        String alias = parts.group(2);
        if (fullName.split(" ").length < 2 && !hasHonorific && alias == null
                || ROLE_OR_DOCUMENT_HEADING.matcher(cleaned).find())
            return new Checked(null, "INVALID_NAME");
        return new Checked(fullName + (alias == null ? "" : " (" + alias + ")"), null);
    }

    private static Checked remotePhone(String raw) {
        String text =
                Normalizer.normalize(raw, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ");
        if (text.isBlank() || text.codePointCount(0, text.length()) > 50)
            return new Checked(null, "VALUE_LENGTH_INVALID");
        if (REMOTE_PHONE_DATE.matcher(text.replace(" ", "")).matches())
            return new Checked(null, "INVALID_PHONE");
        text = REMOTE_PHONE_LABEL.matcher(text).replaceFirst("");
        var candidates = new ArrayList<String>();
        var separators = java.util.regex.Pattern.compile("[/;,]| [-–] ").matcher(text);
        int start = 0;
        while (separators.find()) {
            if (text.substring(start, separators.start()).replaceAll("[^0-9]", "").length() >= 7
                    && text.substring(separators.end()).replaceAll("[^0-9]", "").length() >= 7) {
                candidates.add(text.substring(start, separators.start()).strip());
                start = separators.end();
            }
        }
        candidates.add(text.substring(start).strip());
        // Date-shaped candidates remain invalid even when a different number would be kept.
        for (String candidate : candidates) {
            String number = REMOTE_PHONE_EXTENSION.matcher(candidate).replaceFirst("");
            if (REMOTE_PHONE_DATE.matcher(number.replace(" ", "")).matches())
                return new Checked(null, "INVALID_PHONE");
        }
        String first = candidates.getFirst();
        var extension = REMOTE_PHONE_EXTENSION.matcher(first);
        String suffix = "";
        if (extension.find()) {
            suffix = "x" + extension.group(1);
            first = first.substring(0, extension.start());
        }
        String normalized =
                first.replaceFirst("^\\(\\+([0-9]{1,3})\\)", "+$1")
                        .replaceFirst("^\\(84\\)", "+84")
                        .replaceFirst("^0084", "+84")
                        .replaceFirst("^(\\+[0-9]{1,3}) *\\(0\\)", "$1")
                        .replaceAll("[ .–/(),;\\-]", "");
        if (!normalized.matches("\\+?[0-9]{7,15}")) return new Checked(null, "INVALID_PHONE");
        return new Checked(
                normalized + suffix,
                null,
                candidates.size() > 1 ? "PHONE_MULTIPLE_FIRST_KEPT" : null);
    }

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
