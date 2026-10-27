package com.cvcompass.backend.mapping.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Published mapper settings retain record creator order and arrays; preserve v1 digest bytes. */
public final class MappingDigests {
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

    private MappingDigests() {}

    public static String canonical(Object value) {
        return JSON.writeValueAsString(value);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
