package com.cvcompass.backend.ingestion.controller;

import com.cvcompass.backend.ingestion.config.*;
import com.cvcompass.backend.ingestion.dto.*;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.exception.OcrHttpException;
import com.cvcompass.backend.ingestion.service.OcrWorkerService;
import com.cvcompass.backend.storage.*;
import com.cvcompass.backend.web.ApiResponse;
import com.cvcompass.backend.web.ApiError;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

@RestController
@RequestMapping("/internal/ocr/v1")
@ConditionalOnProperty(prefix="cv.ocr.integration",name="enabled",havingValue="true")
public class OcrWorkerController {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OcrWorkerController.class);
    private final OcrWorkerService service;
    private final OcrIntegrationProperties properties;
    private final OcrJobProperties limits;
    private final ObjectMapper json;
    private final FileStorageService storage;
    public OcrWorkerController(OcrWorkerService service,OcrIntegrationProperties properties,OcrJobProperties limits,
            ObjectMapper json,FileStorageService storage) {
        this.service=service; this.properties=properties; this.limits=limits; this.json=json; this.storage=storage;
    }
    @ExceptionHandler({StorageException.class,org.springframework.dao.TransientDataAccessException.class,
            org.springframework.dao.DataAccessResourceFailureException.class})
    public ResponseEntity<ApiError> unavailable(RuntimeException failure,HttpServletResponse response) {
        com.cvcompass.backend.web.OperationalLog.write(org.slf4j.event.Level.WARN,
                com.cvcompass.backend.web.OperationalLog.Event.OCR_TRANSPORT_FAILED,
                "OCR_UNAVAILABLE", Map.of());
        return ResponseEntity.status(503).body(new ApiError("OCR_UNAVAILABLE","OCR dependency unavailable",
                response.getHeader("X-Correlation-ID"),List.of()));
    }
    @PostMapping("/claims")
    public ResponseEntity<ApiResponse<Map<String,Object>>> claim(HttpServletRequest request) throws IOException {
        var body=parse(body(request,properties.getControlBodyBytes()));
        exact(body,Set.of("protocol","profile_revision")); protocol(body);
        require(body.path("profile_revision").isString(),422,"INVALID_REQUEST");
        var claim=service.claim(principal(request),uuid(singleHeader(request,"Idempotency-Key")),
                tokenHash(request),body.get("profile_revision").asString());
        if (claim.status()==204) return ResponseEntity.noContent().build();
        return ResponseEntity.status(claim.status()).header("Location","/internal/ocr/v1/attempts/"+claim.data().get("attempt_id"))
                .body(new ApiResponse<>(claim.data()));
    }
    @PutMapping("/attempts/{id}/lease")
    public ResponseEntity<ApiResponse<Map<String,Object>>> heartbeat(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        exact(parse(body(request,properties.getControlBodyBytes())),Set.of());
        return ResponseEntity.ok(new ApiResponse<>(service.heartbeat(principal(request),key(id,request))));
    }
    @PutMapping("/attempts/{id}/result")
    public ResponseEntity<ApiResponse<Map<String,Object>>> result(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        String raw=body(request,limits.getMaxEnvelopeBytes());
        JsonNode tree=parse(raw);
        return reply(service.result(principal(request),key(id,request),raw,tree));
    }
    @PutMapping("/attempts/{id}/failure")
    public ResponseEntity<ApiResponse<Map<String,Object>>> failure(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        var tree=parse(body(request,properties.getControlBodyBytes()));
        exact(tree,Set.of("protocol","code")); protocol(tree);
        OcrFailureCode code;
        try { code=OcrFailureCode.valueOf(tree.path("code").asString()); }
        catch (IllegalArgumentException malformed) { throw new OcrHttpException(422,"INVALID_REQUEST"); }
        require(code != OcrFailureCode.LEASE_EXPIRED,422,"INVALID_REQUEST");
        return reply(service.failure(principal(request),key(id,request),code));
    }
    @GetMapping("/attempts/{id}/input")
    public void input(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        require(request.getHeader("Range")==null,416,"RANGE_UNSUPPORTED");
        OcrInput input=service.input(principal(request),key(id,request));
        require(input.sizeBytes()>0 && input.sizeBytes()<=10485760,503,"ADMISSION_UNAVAILABLE");
        // Storage open is outside the locked metadata transaction; no arbitrary path/filename reaches storage.
        try (InputStream source=storage.read(new StorageKey(input.storageKey()))) {
            response.setContentType("application/pdf"); response.setContentLengthLong(input.sizeBytes());
            response.setHeader("X-Content-SHA256",input.sha256());
            response.setHeader("Content-Encoding","identity");
            MessageDigest digest=sha256();
            long total=0, deadline=System.nanoTime()+properties.getDownloadTimeout().toNanos();
            byte[] buffer=new byte[8192];
            int count;
            while ((count=source.read(buffer,0,(int)Math.min(buffer.length,input.sizeBytes()-total+1)))!=-1) {
                require(System.nanoTime()<deadline,503,"DOWNLOAD_TIMEOUT");
                total+=count; require(total<=input.sizeBytes(),503,"SOURCE_CHANGED");
                digest.update(buffer,0,count); response.getOutputStream().write(buffer,0,count);
            }
            require(total==input.sizeBytes() && HexFormat.of().formatHex(digest.digest()).equals(input.sha256()),503,"SOURCE_CHANGED");
        }
    }
    private String body(HttpServletRequest request,int cap) throws IOException {
        String type=request.getContentType(),encoding=request.getHeader("Content-Encoding");
        require(type!=null && type.toLowerCase(Locale.ROOT).matches("application/json(?:;\\s*charset=utf-8)?")
                && (encoding==null || encoding.equalsIgnoreCase("identity")),415,"UNSUPPORTED_MEDIA_TYPE");
        require(request.getContentLengthLong()<=cap,413,"BODY_LIMIT");
        byte[] bytes=request.getInputStream().readNBytes(cap+1);
        require(bytes.length<=cap,413,"BODY_LIMIT");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException malformed) { throw new OcrHttpException(422,"INVALID_UTF8"); }
    }
    private JsonNode parse(String raw) {
        try {
            JsonNode tree=json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            depth(tree,0); return tree;
        } catch (OcrHttpException safe) { throw safe; }
        catch (RuntimeException malformed) { throw new OcrHttpException(422,"INVALID_JSON"); }
    }
    private static void depth(JsonNode node,int level) {
        require(node!=null && level<=32,422,"INVALID_JSON");
        if (node.isContainer()) for (JsonNode child : node) depth(child,level+1);
    }
    private static void exact(JsonNode tree,Set<String> fields) {
        require(tree.isObject() && Set.copyOf(tree.propertyNames()).equals(fields),422,"INVALID_REQUEST");
    }
    private static void protocol(JsonNode tree) {
        require(OcrProtocol.JOB_VERSION.equals(tree.path("protocol").asString()),422,"INVALID_PROTOCOL");
    }
    private static OcrWorkerPrincipal principal(HttpServletRequest request) {
        var principal=request.getAttribute(OcrWorkerPrincipal.ATTRIBUTE);
        require(principal instanceof OcrWorkerPrincipal,401,"UNAUTHORIZED");
        return (OcrWorkerPrincipal)principal;
    }
    private static OcrAttemptKey key(UUID id,HttpServletRequest request) {
        return new OcrAttemptKey(id,principal(request).workerId(),tokenHash(request));
    }
    private static String tokenHash(HttpServletRequest request) {
        String token=singleHeader(request,"X-Lease-Token");
        require(token!=null && token.matches("[A-Za-z0-9_-]{43}"),422,"INVALID_LEASE_TOKEN");
        try { require(Base64.getUrlDecoder().decode(token).length==32,422,"INVALID_LEASE_TOKEN"); }
        catch (IllegalArgumentException malformed) { throw new OcrHttpException(422,"INVALID_LEASE_TOKEN"); }
        return OcrWorkerAuthenticationFilter.hash(token);
    }
    private static String singleHeader(HttpServletRequest request,String name) {
        require(Collections.list(request.getHeaders(name)).size()==1,422,"INVALID_HEADER");
        return request.getHeader(name);
    }
    private static UUID uuid(String text) {
        try { UUID value=UUID.fromString(text); require(value.toString().equals(text),422,"INVALID_IDEMPOTENCY_KEY"); return value; }
        catch (IllegalArgumentException malformed) { throw new OcrHttpException(422,"INVALID_IDEMPOTENCY_KEY"); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseEntity<ApiResponse<Map<String,Object>>> reply(OcrHttpReply reply) {
        if (reply.status()==204) return ResponseEntity.noContent().build();
        return ResponseEntity.status(reply.status()).body(new ApiResponse<>(reply.data()));
    }
    private static void require(boolean value,int status,String code) { if (!value) throw new OcrHttpException(status,code); }
}
