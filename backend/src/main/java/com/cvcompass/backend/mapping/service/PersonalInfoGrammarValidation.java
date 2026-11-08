package com.cvcompass.backend.mapping.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Field-specific normalization never repairs OCR characters or invents a country. */
final class PersonalInfoGrammarValidation {
    private static final Pattern PREFIX =
            Pattern.compile("(?iu)^(?:(?:Mr|Mrs|Ms)\\.?\\s+|(?:Ông|Bà|Anh|Chị)\\s*[-–—,:：]\\s*)");
    private static final Pattern SUFFIX =
            Pattern.compile(
                    "(?iu)(?:\\s*[-–—,:：]\\s*(?:Mr|Mrs|Ms|Ông|Bà|Anh|Chị)\\.?|\\s+(?:Mr|Mrs|Ms)\\.)$");
    private static final Pattern OCCUPATION =
            Pattern.compile(
                    "(?iu)\\b(?:engineer|developer|manager|director|officer|analyst|owner|lead|head|executive|consultant|recruiter|student|coordinator|kỹ sư|giám đốc|chuyên viên|trưởng phòng|nhân viên|sinh viên)\\b");

    private static final Pattern ORGANIZATION =
            Pattern.compile(
                    "(?iu)\\b(?:company|corporation|corp|ltd|limited|inc|llc|jsc|group|công ty|tập đoàn)\\b");

    private static final Pattern PHONE_TRUNK =
            Pattern.compile("(?s)^(?:\\+(\\d{1,3})|\\(\\+(\\d{1,3})\\))\\s*\\(0\\)\\s*(.*)$");

    record Checked(String raw, String normalized, String issue) {}

    static String name(String raw) {
        // Vietnamese address terms can also be legal name tokens; whitespace alone is not proof.
        return SUFFIX.matcher(PREFIX.matcher(raw.strip()).replaceFirst(""))
                .replaceFirst("")
                .strip();
    }

    static boolean nameFragment(String text) {
        return text.matches("[\\p{L}\\p{M}][\\p{L}\\p{M}'’-]*") && !OCCUPATION.matcher(text).find();
    }

    static boolean occupation(String text) {
        return OCCUPATION.matcher(text).find() || ORGANIZATION.matcher(text).find();
    }

    static boolean headerName(String raw) {
        String value = name(Normalizer.normalize(raw, Normalizer.Form.NFC));
        String[] words = value.replaceAll("[()]", " ").strip().split("\\s+");
        if (words.length < 2 || words.length > 8 || value.endsWith(".")) return false;
        for (String word : words) {
            if (!Character.isUpperCase(word.codePointAt(0))
                    && !java.util.Set.of("de", "del", "van", "von", "da").contains(word))
                return false;
        }
        return true;
    }

    static Checked check(String field, String raw, String logical) {
        return check(field, raw, logical, false);
    }

    static Checked check(String field, String raw, String logical, boolean v32) {
        String value = Normalizer.normalize(logical, Normalizer.Form.NFC).strip();
        if (!field.equals("summary")) value = value.replaceAll("(?U)\\s+", " ");
        else value = value.replace("\r\n", "\n").replace('\r', '\n');
        int limit =
                switch (field) {
                    case "fullName", "email", "location" -> 255;
                    case "phone" -> 50;
                    default -> 10000;
                };
        if (value.isBlank() || value.codePointCount(0, value.length()) > limit)
            return invalid(raw, "VALUE_LENGTH_INVALID");
        return switch (field) {
            case "summary", "location" -> new Checked(raw, value, null);
            case "gender" ->
                    switch (value.toLowerCase(Locale.ROOT)) {
                        case "male", "nam", "m" -> new Checked(raw, "MALE", null);
                        case "female", "nữ", "f" -> new Checked(raw, "FEMALE", null);
                        default -> invalid(raw, "UNRECOGNIZED_GENDER");
                    };
            case "email" -> {
                if (!value.matches("[^\\s@<>]+@[^\\s@<>.]+(?:\\.[^\\s@<>.]+)+")
                        || value.contains("..")) yield invalid(raw, "INVALID_EMAIL");
                int at = value.lastIndexOf('@');
                yield new Checked(
                        raw,
                        value.substring(0, at + 1)
                                + value.substring(at + 1).toLowerCase(Locale.ROOT),
                        null);
            }
            case "phone" -> phone(raw, value, v32);
            case "fullName" -> {
                String cleaned = name(value);
                if (!cleaned.matches("[\\p{L}\\p{M}][\\p{L}\\p{M} .'’()\\-]*")
                        || cleaned.replaceAll("[()]", " ").strip().split("\\s+").length < 2
                        || occupation(cleaned)
                        || !balanced(cleaned)) yield invalid(raw, "INVALID_NAME");
                yield new Checked(name(raw), cleaned, null);
            }
            default -> throw new IllegalArgumentException("Unsupported personal field");
        };
    }

    private static Checked phone(String raw, String value, boolean v32) {
        var trunk = PHONE_TRUNK.matcher(value);
        if (!trunk.matches()) return standardPhone(raw, value);
        if (!v32) return invalid(raw, "PHONE_TRUNK_NOTATION_AMBIGUOUS");
        if (!trunk.group(3).matches("[0-9].*")) return invalid(raw, "INVALID_PHONE");
        boolean vietnamese = "84".equals(trunk.group(1)) || "84".equals(trunk.group(2));
        var checked = standardPhone(raw, vietnamese ? "0" + trunk.group(3) : value);
        if (checked.normalized() == null) return checked;
        return new Checked(
                raw,
                checked.normalized(),
                vietnamese ? "PHONE_TRUNK_NOTATION_NORMALIZED" : "PHONE_TRUNK_NOTATION_KEPT");
    }

    private static Checked standardPhone(String raw, String value) {
        String compact = value.replaceAll("\\s+", "");
        if (compact.matches(
                "(?:[12]\\d{3}[-/.][01]?\\d[-/.][0-3]?\\d|[0-3]?\\d[-/.][01]?\\d[-/.][12]\\d{3}|[12]\\d{3}[-/.–—][12]\\d{3})"))
            return invalid(raw, "INVALID_PHONE");
        String checked = value.replaceFirst("^\\(\\+(\\d{1,3})\\)", "+$1");
        if (!checked.matches("\\+?[0-9(). /–—-]+(?:\\s*(?iu:ext\\.?|x|máy lẻ)\\s*\\d{1,8})?"))
            return invalid(raw, "INVALID_PHONE");
        String normalized =
                checked.replaceFirst("(?iu)\\s*(?:ext\\.?|x|máy lẻ)\\s*", "x")
                        .replaceAll("[(). /–—-]", "");
        int digits = normalized.split("x", 2)[0].replace("+", "").length();
        return digits >= 7 && digits <= 15
                ? new Checked(raw, normalized, null)
                : invalid(raw, "INVALID_PHONE");
    }

    private static boolean balanced(String text) {
        int depth = 0;
        for (char ch : text.toCharArray()) {
            if (ch == '(' && ++depth > 1) return false;
            if (ch == ')' && --depth < 0) return false;
        }
        return depth == 0;
    }

    private static Checked invalid(String raw, String code) {
        return new Checked(raw, null, code);
    }
}
