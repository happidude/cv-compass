package com.cvcompass.backend.mapping.api;

import com.cvcompass.backend.mapping.service.PersonalInfoProvinces;

/** Conservative lookup using the unchanged engine 3.3 province catalog and aliases. */
public final class Provinces {
    private Provinces() {}

    public static String canonical(String location) {
        if (location == null || location.isBlank()) return null;
        String remaining = location;
        String canonical = null;
        PersonalInfoProvinces.Match match;
        while ((match = PersonalInfoProvinces.find(remaining)) != null) {
            if (canonical != null && !canonical.equals(match.canonical())) return null;
            canonical = match.canonical();
            remaining =
                    remaining.substring(0, match.start())
                            + " ".repeat(match.end() - match.start())
                            + remaining.substring(match.end());
        }
        return canonical;
    }
}
