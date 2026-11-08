package com.cvcompass.backend.mapping.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finite Vietnamese province/city catalog for the 3.3 grammar. Names are kept as written in the
 * source (pre- and post-2025 units both resolve to their own name); the grammar never converts a
 * former province into the unit it was merged into, because that would be inferred data.
 */
final class PersonalInfoProvinces {
    record Match(String canonical, int start, int end) {}

    private record Alias(String canonical, Pattern pattern, int length) {}

    private static final List<String> CANONICAL =
            List.of(
                    "An Giang",
                    "Bà Rịa - Vũng Tàu",
                    "Bắc Giang",
                    "Bắc Kạn",
                    "Bạc Liêu",
                    "Bắc Ninh",
                    "Bến Tre",
                    "Bình Định",
                    "Bình Dương",
                    "Bình Phước",
                    "Bình Thuận",
                    "Cà Mau",
                    "Cần Thơ",
                    "Cao Bằng",
                    "Đà Nẵng",
                    "Đắk Lắk",
                    "Đắk Nông",
                    "Điện Biên",
                    "Đồng Nai",
                    "Đồng Tháp",
                    "Gia Lai",
                    "Hà Giang",
                    "Hà Nam",
                    "Hà Nội",
                    "Hà Tĩnh",
                    "Hải Dương",
                    "Hải Phòng",
                    "Hậu Giang",
                    "Hòa Bình",
                    "Hồ Chí Minh",
                    "Huế",
                    "Hưng Yên",
                    "Khánh Hòa",
                    "Kiên Giang",
                    "Kon Tum",
                    "Lai Châu",
                    "Lâm Đồng",
                    "Lạng Sơn",
                    "Lào Cai",
                    "Long An",
                    "Nam Định",
                    "Nghệ An",
                    "Ninh Bình",
                    "Ninh Thuận",
                    "Phú Thọ",
                    "Phú Yên",
                    "Quảng Bình",
                    "Quảng Nam",
                    "Quảng Ngãi",
                    "Quảng Ninh",
                    "Quảng Trị",
                    "Sóc Trăng",
                    "Sơn La",
                    "Tây Ninh",
                    "Thái Bình",
                    "Thái Nguyên",
                    "Thanh Hóa",
                    "Thừa Thiên Huế",
                    "Tiền Giang",
                    "Trà Vinh",
                    "Tuyên Quang",
                    "Vĩnh Long",
                    "Vĩnh Phúc",
                    "Yên Bái");

    // Common English/abbreviated spellings seen in CVs. Ambiguous abbreviations (ĐN, BD, HP) are
    // deliberately absent: they name more than one province.
    private static final Map<String, List<String>> EXTRA =
            Map.of(
                    "Hồ Chí Minh",
                    List.of("HCMC", "HCM", "TP.HCM", "TPHCM", "Saigon", "Sài Gòn", "Ho Chi Minh"),
                    "Hà Nội",
                    List.of("Hanoi", "HN"),
                    "Đà Nẵng",
                    List.of("Danang"),
                    "Hải Phòng",
                    List.of("Haiphong"),
                    "Cần Thơ",
                    List.of("Cantho"),
                    "Bà Rịa - Vũng Tàu",
                    List.of("Vũng Tàu", "BRVT"),
                    "Đắk Lắk",
                    List.of("Daklak", "Dak Lak", "Đắc Lắc"),
                    "Huế",
                    List.of("Hue City"));

    private static final List<Alias> ALIASES = aliases();

    private PersonalInfoProvinces() {}

    /** Returns the province named last in the text (addresses end with the widest unit). */
    static Match find(String text) {
        var folded = fold(text);
        Match best = null;
        int bestLength = 0;
        for (var alias : ALIASES) {
            var matcher = alias.pattern().matcher(folded.text());
            while (matcher.find()) {
                int end = folded.origin(matcher.end() - 1) + 1;
                if (best == null
                        || end > best.end()
                        || end == best.end() && alias.length() > bestLength) {
                    best = new Match(alias.canonical(), folded.origin(matcher.start()), end);
                    bestLength = alias.length();
                }
            }
        }
        if (best == null) return null;
        // Extend the end over a combining mark that belongs to the last matched letter.
        int end = best.end();
        while (end < text.length()
                && Character.getType(text.charAt(end)) == Character.NON_SPACING_MARK) end++;
        return new Match(best.canonical(), best.start(), end);
    }

    private static List<Alias> aliases() {
        var names = new LinkedHashMap<String, String>();
        for (String name : CANONICAL) names.put(fold(name).text(), name);
        EXTRA.forEach(
                (canonical, values) -> values.forEach(v -> names.put(fold(v).text(), canonical)));
        var result = new ArrayList<Alias>();
        names.forEach(
                (key, canonical) ->
                        result.add(
                                new Alias(
                                        canonical,
                                        Pattern.compile(
                                                "(?<![a-z0-9])"
                                                        + Pattern.quote(key)
                                                                .replace(" ", "\\E ?\\Q")
                                                        + "(?![a-z0-9])"),
                                        key.length())));
        return List.copyOf(result);
    }

    private record Folded(String text, int[] origins) {
        int origin(int index) {
            return origins[index];
        }
    }

    /** Lowercase ASCII skeleton with an index map back to the source characters. */
    private static Folded fold(String text) {
        var out = new StringBuilder();
        var origins = new ArrayList<Integer>();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String base =
                    ch == 'đ' || ch == 'Đ'
                            ? "d"
                            : Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD)
                                    .replaceAll("\\p{M}", "")
                                    .toLowerCase(Locale.ROOT);
            if (base.isEmpty()) continue;
            for (char c : base.toCharArray()) {
                boolean word = c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
                if (!word && (out.isEmpty() || out.charAt(out.length() - 1) == ' ')) continue;
                out.append(word ? c : ' ');
                origins.add(i);
            }
        }
        var array = origins.stream().mapToInt(Integer::intValue).toArray();
        return new Folded(out.toString(), array);
    }
}
