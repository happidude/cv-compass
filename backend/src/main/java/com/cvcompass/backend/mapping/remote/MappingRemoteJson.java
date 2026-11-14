package com.cvcompass.backend.mapping.remote;

import static com.cvcompass.backend.mapping.remote.MappingRemoteException.require;

import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MappingRemoteJson {
    public static final String PROTOCOL = "cv-compass.llm-mapping/1.0";

    private MappingRemoteJson() {}

    public static JsonNode parse(String raw, ObjectMapper json) {
        try {
            JsonNode tree =
                    json.reader()
                            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(raw);
            bounds(tree, 0);
            return tree;
        } catch (MappingRemoteException safe) {
            throw safe;
        } catch (RuntimeException malformed) {
            throw new MappingRemoteException(422, "INVALID_RESULT");
        }
    }

    private static void bounds(JsonNode node, int depth) {
        require(node != null && depth <= 32, 422, "INVALID_RESULT");
        if (node.isString())
            require(
                    node.asString().codePointCount(0, node.asString().length()) <= 2000,
                    422,
                    "INVALID_RESULT");
        if (node.isContainer()) for (JsonNode child : node) bounds(child, depth + 1);
    }

    public static void exact(JsonNode node, Set<String> names) {
        require(node.isObject() && node.propertyNames().equals(names), 422, "INVALID_RESULT");
    }

    public static String string(JsonNode node) {
        require(node.isString(), 422, "INVALID_RESULT");
        return node.asString();
    }

    public static String nullable(JsonNode node) {
        return node.isNull() ? null : string(node);
    }

    public static void protocol(JsonNode node) {
        require(
                node.path("protocol").isString()
                        && PROTOCOL.equals(node.path("protocol").asString()),
                422,
                "UNSUPPORTED_PROTOCOL");
    }

    public static JsonNode provenance(JsonNode node) {
        exact(node, Set.of("model", "promptVersion", "workerVersion"));
        for (JsonNode value : node)
            require(string(value).matches("[A-Za-z0-9._:/@+-]{1,128}"), 422, "INVALID_RESULT");
        return node;
    }
}
