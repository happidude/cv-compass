package com.cvcompass.backend.ingestion.controller;

import com.cvcompass.backend.ingestion.config.*;
import com.cvcompass.backend.ingestion.dto.*;
import com.cvcompass.backend.ingestion.exception.OcrHttpException;
import com.cvcompass.backend.ingestion.service.OcrWorkerService;
import com.cvcompass.backend.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Boundary unit tests, not PostgreSQL or resource-isolation evidence. */
class OcrWorkerControllerTests {
    private final OcrWorkerService service=mock(OcrWorkerService.class);
    private final FileStorageService storage=mock(FileStorageService.class);
    private final OcrJobProperties limits=new OcrJobProperties();
    private final OcrIntegrationProperties config=new OcrIntegrationProperties();
    private final OcrWorkerController controller=new OcrWorkerController(service,config,limits,JsonMapper.builder().build(),storage);
    private final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private MockHttpServletRequest request(byte[] body) {
        var request=new MockHttpServletRequest(); request.setContentType("application/json"); request.setContent(body);
        request.addHeader("X-Lease-Token",Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        request.setAttribute(OcrWorkerPrincipal.ATTRIBUTE,new OcrWorkerPrincipal("worker",Set.of("test-v1")));
        return request;
    }
    private MockHttpServletRequest request(String body) { return request(body.getBytes(StandardCharsets.UTF_8)); }
    private void rejected(int status,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(OcrHttpException.class,e->assertThat(e.status()).isEqualTo(status));
    }
    @Test void unknownDuplicateTrailingAndNonfiniteBodiesRejected() {
        for (String body:List.of("{\"x\":1}","{\"x\":1,\"x\":2}","{} {}","{\"x\":NaN}"))
            rejected(422,()->controller.heartbeat(id,request(body)));
        verifyNoInteractions(service);
    }
    @Test void invalidUtf8AndDepthAreRejected() {
        rejected(422,()->controller.heartbeat(id,request(new byte[]{(byte)0xc3,0x28})));
        rejected(422,()->controller.heartbeat(id,request("[".repeat(34)+"0"+"]".repeat(34))));
        verifyNoInteractions(service);
    }
    @Test void mediaCompressionAndBodyCapsAreEnforcedBeforeService() {
        var plain=request("{}"); plain.setContentType("text/plain");
        rejected(415,()->controller.heartbeat(id,plain));
        var compressed=request("{}"); compressed.addHeader("Content-Encoding","gzip");
        rejected(415,()->controller.heartbeat(id,compressed));
        config.setControlBodyBytes(3); rejected(413,()->controller.heartbeat(id,request("    {}")));
        verifyNoInteractions(service);
    }
    @Test void exactUtf8SubmissionIncludingWhitespaceIsPreserved() throws Exception {
        String raw=" {\"ocr_result\":{\"text\":\"Tiếng Việt\\n\"}}\r\n";
        when(service.result(any(),any(),eq(raw),any())).thenReturn(new OcrHttpReply(201,Map.of("accepted",true)));
        assertThat(controller.result(id,request(raw)).getStatusCode().value()).isEqualTo(201);
        verify(service).result(any(),any(),eq(raw),any());
    }
    @Test void claimRejectsIdentityFromBodyAndNoncanonicalUuid() {
        rejected(422,()->controller.claim(request("{\"worker_id\":\"attacker\"}")));
        var request=request("{\"protocol\":\"cv-compass.ocr-job/1.0\",\"profile_revision\":\"test-v1\"}");
        request.addHeader("Idempotency-Key","1-1-1-1-1"); rejected(422,()->controller.claim(request));
        verifyNoInteractions(service);
    }
    @Test void noJobIsEmpty204() throws Exception {
        var request=request("{\"protocol\":\"cv-compass.ocr-job/1.0\",\"profile_revision\":\"test-v1\"}");
        request.addHeader("Idempotency-Key",id.toString());
        when(service.claim(any(),eq(id),anyString(),eq("test-v1"))).thenReturn(new OcrHttpReply(204,Map.of()));
        assertThat(controller.claim(request).getBody()).isNull();
    }
    @Test void unknownOrReservedFailureIsRejected() {
        for (String code:List.of("LEASE_EXPIRED","invented"))
            rejected(422,()->controller.failure(id,request("{\"protocol\":\"cv-compass.ocr-job/1.0\",\"code\":\""+code+"\"}")));
        verifyNoInteractions(service);
    }
    @Test void inputRejectsRangeBeforeOpeningStorage() {
        var request=request(""); request.addHeader("Range","bytes=0-1");
        rejected(416,()->controller.input(id,request,new MockHttpServletResponse())); verifyNoInteractions(service,storage);
    }
    @Test void inputStreamsOpaqueKeyAndClosesSource() throws Exception {
        byte[] pdf="%PDF-synthetic".getBytes(StandardCharsets.US_ASCII);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pdf));
        when(service.input(any(),any())).thenReturn(new OcrInput("a".repeat(32),pdf.length,hash));
        var source=spy(new ByteArrayInputStream(pdf)); when(storage.read(any())).thenReturn(source);
        var response=new MockHttpServletResponse(); controller.input(id,request(""),response);
        assertThat(response.getContentAsByteArray()).isEqualTo(pdf); verify(source).close();
    }
    @Test void wrongSizeOrHashIsNotSilentlyAccepted() {
        when(service.input(any(),any())).thenReturn(new OcrInput("a".repeat(32),2,"b".repeat(64)));
        when(storage.read(any())).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        rejected(503,()->controller.input(id,request(""),new MockHttpServletResponse()));
    }
}
