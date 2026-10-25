package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.OcrJobProperties;
import com.cvcompass.backend.ingestion.dto.OcrResultPayload;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.exception.OcrStateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

/** Persistence admission, not HTTP authentication or deployment-provenance attestation. */
@Service
@ConditionalOnProperty(prefix = "cv.ocr", name = "enabled", havingValue = "true")
public class OcrResultValidator {
    private final ObjectMapper json;
    private final OcrJobProperties limits;
    private final JsonNode definitions;
    private final JsonNode legacyDefinitions;

    public OcrResultValidator(ObjectMapper json, OcrJobProperties limits,
            @Value("classpath:ocr/ocr-v2.schema.json") Resource schema,
            @Value("classpath:ocr/ocr-v1.schema.json") Resource legacySchema) throws IOException {
        this.json = json; this.limits = limits;
        try (var input = schema.getInputStream()) { definitions = json.readTree(input).get("$defs"); }
        try (var input = legacySchema.getInputStream()) { legacyDefinitions = json.readTree(input).get("$defs"); }
    }

    public OcrResultPayload parse(String raw) {
        try {
            require(raw != null && raw.length() <= limits.getMaxEnvelopeBytes()
                    && raw.getBytes(StandardCharsets.UTF_8).length <= limits.getMaxEnvelopeBytes());
            JsonNode envelope = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            depth(envelope,0);
            exact(envelope,Set.of("protocol","profile_revision","exit_code","delivered","provenance","ocr_result"));
            require(OcrProtocol.JOB_VERSION.equals(text(envelope.get("protocol")))
                    && envelope.get("delivered").isBoolean() && envelope.get("delivered").booleanValue());
            String profile = text(envelope.get("profile_revision"));
            require(profile.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"));
            JsonNode provenance = envelope.get("provenance");
            exact(provenance,Set.of("worker_commit","image_id"));
            require(text(provenance.get("worker_commit")).matches("[0-9a-f]{40}")
                    && text(provenance.get("image_id")).matches("sha256:[0-9a-f]{64}"));
            JsonNode result = envelope.get("ocr_result");
            object(result,"result");
            String version = text(result.get("schema_version"));
            boolean legacy = OcrProtocol.LEGACY_RESULT_VERSION.equals(version);
            require((legacy || OcrProtocol.RESULT_VERSION.equals(version))
                    && "result".equals(text(result.get("type"))));
            object(result,"result",legacy ? legacyDefinitions : definitions);
            String status = text(result.get("status"));
            int exit = switch (status) { case "success" -> 0; case "partial" -> 1; case "failed" -> 2; default -> throw invalid(); };
            require(integer(envelope.get("exit_code"),0,2) && envelope.get("exit_code").intValue() == exit);
            String request = text(result.get("request_id")), document = text(result.get("document_id"));
            require(request.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") && document.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"));
            JsonNode pages = result.get("pages"), errors = result.get("errors");
            require(pages.isArray() && pages.size() <= limits.getMaxPages() && errors.isArray() && errors.size() <= 32);
            JsonNode count = result.get("page_count");
            require(count.isNull() ? pages.isEmpty() && status.equals("failed")
                    : integer(count,1,limits.getMaxPages()) && count.intValue() == pages.size());
            for (JsonNode error : errors) error(error);
            warnings(result.get("warnings"));
            timings(result.get("timings_ms"),Set.of("preflight","cleanup","total"));
            JsonNode fingerprint = result.get("config_fingerprint");
            require(fingerprint.isNull() || text(fingerprint).matches("[0-9a-f]{64}"));
            engines(result.get("engines"));
            boolean ok = false, pageFailure = false, nativePage = false, ocrPage = false;
            long characters = 0;
            int number = 0;
            for (JsonNode page : pages) {
                object(page,"page",legacy ? legacyDefinitions : definitions);
                require(integer(page.get("page_number"),1,limits.getMaxPages()) && page.get("page_number").intValue() == ++number);
                String pageStatus = text(page.get("status")), method = text(page.get("method"));
                String source = text(page.get("raw_text"));
                String value = legacy ? text(page.get("text")) : null;
                int n = legacy ? value.codePointCount(0,value.length()) : 0;
                int m = source.codePointCount(0,source.length());
                require(n <= limits.getMaxTextCodePoints() && m <= limits.getMaxTextCodePoints());
                if (legacy) require(!value.contains("\r") && Normalizer.isNormalized(value,Normalizer.Form.NFC));
                characters += (long)n + m;
                warnings(page.get("warnings"));
                timings(page.get("timings_ms"),Set.of("native","render","preprocess","ocr","total"));
                switch (pageStatus) {
                    case "ok" -> { require(Set.of("native","ocr").contains(method)
                            && usable(legacy ? value : source) && page.get("error").isNull()); ok = true; }
                    case "blank" -> require(method.equals("none") && (!legacy || value.isEmpty()) && source.isEmpty()
                            && page.get("error").isNull() && contains(page.get("warnings"),"BLANK_PAGE"));
                    case "failed", "not_processed" -> { require(method.equals("none") && (!legacy || value.isEmpty())); error(page.get("error")); pageFailure = true; }
                    default -> throw invalid();
                }
                nativePage |= method.equals("native"); ocrPage |= method.equals("ocr");
                confidence(page.get("confidence"),method);
            }
            require(characters <= limits.getMaxDocumentText());
            String actual = !ok ? "failed" : pageFailure || !errors.isEmpty() ? "partial" : "success";
            require(status.equals(actual));
            String resultJson = json.writeValueAsString(result);
            require(resultJson.getBytes(StandardCharsets.UTF_8).length <= limits.getMaxResultBytes());
            ExtractionMethod method = nativePage && ocrPage ? ExtractionMethod.MIXED : nativePage
                    ? ExtractionMethod.TEXT_EXTRACTION : ocrPage ? ExtractionMethod.OCR : ExtractionMethod.NONE;
            return new OcrResultPayload(profile,version,request,document,status,method,resultJson,json.writeValueAsString(provenance),digest(raw));
        } catch (OcrStateException known) { throw known; }
        catch (RuntimeException malformed) { throw invalid(); }
    }

    private void object(JsonNode node,String name) {
        object(node,name,definitions);
    }
    private void object(JsonNode node,String name,JsonNode versionDefinitions) {
        JsonNode definition = versionDefinitions.get(name);
        require(node != null && node.isObject() && definition.get("properties").propertyNames().containsAll(node.propertyNames()));
        for (JsonNode key : definition.get("required")) require(node.has(key.asString()));
    }
    public OcrEndReason failedReason(OcrResultPayload result) {
        return Set.of("INVALID_CONFIGURATION","DEPENDENCY_MISSING","MODEL_MISSING","ENGINE_VERSION_UNSUPPORTED")
                .contains(failedCode(result)) ? OcrEndReason.CONFIGURATION_ERROR : OcrEndReason.OCR_FAILED;
    }
    public String failedCode(OcrResultPayload result) {
        JsonNode tree = json.readTree(result.resultJson());
        for (JsonNode error : tree.get("errors")) {
            String code=error.get("code").asString();
            if (Set.of("INVALID_CONFIGURATION","DEPENDENCY_MISSING","MODEL_MISSING","ENGINE_VERSION_UNSUPPORTED").contains(code)) return code;
        }
        return tree.get("errors").isEmpty() ? "OCR_FAILED" : tree.get("errors").get(0).get("code").asString();
    }
    private static void exact(JsonNode node,Set<String> keys) { require(node != null && node.isObject() && Set.copyOf(node.propertyNames()).equals(keys)); }
    private void error(JsonNode node) {
        object(node,"error");
        for (String field : Set.of("code","stage")) require(contains(definitions.get("error").get("properties").get(field).get("enum"),text(node.get(field))));
    }
    private void warnings(JsonNode node) {
        require(node.isArray() && node.size() <= definitions.get("warnings").get("maxItems").intValue());
        Set<String> seen = new HashSet<>();
        for (JsonNode warning : node) require(seen.add(text(warning)) && contains(definitions.get("warnings").get("items").get("enum"),text(warning)));
    }
    private static void timings(JsonNode node,Set<String> keys) {
        exact(node,keys);
        for (String key : keys) {
            JsonNode value = node.get(key);
            require(!key.equals("total") && value.isNull() || value.isNumber() && Double.isFinite(value.doubleValue()) && value.doubleValue() >= 0);
        }
    }
    private void engines(JsonNode node) {
        object(node,"engines");
        for (String name : node.propertyNames()) {
            JsonNode value = node.get(name);
            if (!name.equals("models")) require(value.isNull() || value.isString());
        }
        JsonNode models = node.get("models");
        require(models.isArray() && models.size() <= 3);
        for (JsonNode model : models) {
            exact(model,Set.of("name","revision","sha256"));
            require(Set.of("vie","eng","osd").contains(text(model.get("name"))));
            require(model.get("revision").isNull() || text(model.get("revision")).matches("[0-9a-f]{40}"));
            require(model.get("sha256").isNull() || text(model.get("sha256")).matches("[0-9a-f]{64}"));
        }
    }
    private void confidence(JsonNode node,String method) {
        if (node.isNull()) return;
        require(method.equals("ocr")); object(node,"confidence");
        JsonNode sampleCount = definitions.get("confidence").get("properties").get("sample_count");
        require(text(node.get("engine")).equals("tesseract") && text(node.get("metric")).equals("mean_word_confidence")
                && node.get("value").isNumber() && Double.isFinite(node.get("value").doubleValue())
                && node.get("value").doubleValue() >= 0 && node.get("value").doubleValue() <= 100
                && integer(node.get("sample_count"),sampleCount.get("minimum").intValue(),sampleCount.get("maximum").intValue()));
    }
    private static String text(JsonNode node) { require(node != null && node.isString()); return node.asString(); }
    private static boolean usable(String value) {
        // Match Python str.strip / Unicode whitespace, including NBSP, narrow NBSP and NEL.
        return value.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != 0x85);
    }
    private static boolean contains(JsonNode array,String value) { for (JsonNode node : array) if (value.equals(node.asString())) return true; return false; }
    private static boolean integer(JsonNode node,int min,int max) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= min && node.intValue() <= max;
    }
    private static void depth(JsonNode node,int level) {
        require(level <= 32);
        if (node.isString()) {
            String value=node.asString();
            for (int i=0;i<value.length();i++) {
                char c=value.charAt(i); require(c != 0);
                if (Character.isHighSurrogate(c)) { require(i+1<value.length() && Character.isLowSurrogate(value.charAt(++i))); }
                else require(!Character.isLowSurrogate(c));
            }
        }
        if (node.isContainer()) for (JsonNode child : node) depth(child,level+1);
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static OcrStateException invalid() { return new OcrStateException(OcrStateError.INVALID_RESULT); }
    public static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
