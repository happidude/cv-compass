package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.OcrIntegrationProperties;
import com.cvcompass.backend.ingestion.dto.*;
import com.cvcompass.backend.ingestion.entity.*;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.exception.OcrHttpException;
import com.cvcompass.backend.ingestion.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OcrWorkerServiceTests {
    private final OcrJobService jobs=mock(OcrJobService.class);
    private final OcrStateRepository state=mock(OcrStateRepository.class);
    private final CvProcessingJobRepository records=mock(CvProcessingJobRepository.class);
    private final OcrIntegrationProperties config=new OcrIntegrationProperties();
    private final JsonMapper json=JsonMapper.builder().build();
    private final OcrWorkerService service=new OcrWorkerService(jobs,state,records,config,json);
    private final UUID id=UUID.randomUUID();
    private final OcrWorkerPrincipal principal=new OcrWorkerPrincipal("worker",Set.of("test-v1"));
    private final OcrAttemptKey key=new OcrAttemptKey(id,"worker","a".repeat(64));
    private CvProcessingJob seed() {
        config.setNamespace("synthetic-only");
        var document=new CvDocument(); ReflectionTestUtils.setField(document,"id",2L);
        document.setStoragePath("a".repeat(32)); document.setFileSize(20L); document.setSha256("b".repeat(64));
        var job=new CvProcessingJob(); ReflectionTestUtils.setField(job,"id",1L); job.setDocument(document);
        job.setProfileRevision("test-v1");
        job.setAdmissionSnapshot(Map.of("namespace","synthetic-only","fixture_id","synthetic", "document_id","2",
                "sha256","b".repeat(64),"size_bytes",20L,"worker_commit","c".repeat(40),"image_id","sha256:"+"d".repeat(64),
                "config_fingerprint","e".repeat(64),"engines",Map.of("python","3.14.8","models",List.of())));
        when(records.findById(1L)).thenReturn(Optional.of(job));
        when(state.findAttempt(id)).thenReturn(Optional.of(new OcrAttemptState(3,id,1,1,"worker",id,"a".repeat(64),
                "a".repeat(64),Instant.MAX,Instant.MAX,Instant.MAX,OcrAttemptStatus.ACTIVE,null)));
        return job;
    }
    private OcrClaim claim() {
        return new OcrClaim(1,2,"test-v1",id,1,Instant.EPOCH,Instant.EPOCH.plusSeconds(120),Instant.EPOCH.plusSeconds(900));
    }
    @Test void forbiddenProfileRejectedBeforeQueueAccess() {
        assertThatThrownBy(()->service.claim(principal,id,"a".repeat(64),"forbidden"))
                .isInstanceOf(OcrHttpException.class); verifyNoInteractions(state,jobs,records);
    }
    @Test void claimBindsManifestIdentityAndReplayStatus() {
        seed(); when(jobs.claim(any())).thenReturn(Optional.of(claim()));
        when(state.findClaim("worker",id)).thenReturn(Optional.empty());
        assertThat(service.claim(principal,id,"a".repeat(64),"test-v1").status()).isEqualTo(201);
        var previous=state.findAttempt(id);
        when(state.findClaim("worker",id)).thenReturn(previous);
        var replay=service.claim(principal,id,"a".repeat(64),"test-v1");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.data()).containsEntry("synthetic_only",true).containsEntry("size_bytes",20L);
    }
    @Test void foreignWorkerCannotReachHeartbeatStateMachine() {
        seed();
        assertThatThrownBy(()->service.heartbeat(new OcrWorkerPrincipal("other",Set.of("test-v1")),key))
                .isInstanceOfSatisfying(OcrHttpException.class,e->assertThat(e.status()).isEqualTo(403));
        verifyNoInteractions(jobs);
    }
    @Test void localClaimReportsActualAdmissionWithoutSyntheticFixture() {
        var job = seed();
        var snapshot = new HashMap<>(job.getAdmissionSnapshot());
        snapshot.remove("fixture_id");
        snapshot.put("admission_mode", "local");
        job.setAdmissionSnapshot(snapshot);
        when(jobs.claim(any())).thenReturn(Optional.of(claim()));
        var reply = service.claim(principal, id, "a".repeat(64), "test-v1");
        assertThat(reply.data()).containsEntry("synthetic_only", false).containsEntry("local_only", true);
    }
    @Test void snapshotMismatchRejectsInputBeforeStorageDescriptorIsReturned() {
        var job=seed(); job.getDocument().setSha256("f".repeat(64));
        when(jobs.authorizeInput(key)).thenReturn(claim());
        assertThatThrownBy(()->service.input(principal,key)).isInstanceOf(OcrHttpException.class);
    }
    private String envelope(boolean preflight) {
        return "{\"provenance\":{\"worker_commit\":\""+"c".repeat(40)+"\",\"image_id\":\"sha256:"+"d".repeat(64)
                +"\"},\"ocr_result\":{\"schema_version\":\"cv-compass.ocr/1.0\",\"status\":\"failed\",\"page_count\":"+(preflight?"null":"1")
                +",\"config_fingerprint\":null,\"engines\":{\"python\":null,\"models\":[]}}}";
    }
    @Test void preflightNullMetadataRemainsNullAndReplayReceiptComesFromStateMachine() {
        seed(); var raw=envelope(true);
        when(jobs.complete(key,raw)).thenReturn(new OcrReceipt(1,id,4L,true));
        assertThat(service.result(principal,key,raw,json.readTree(raw)).status()).isEqualTo(200);
        verify(jobs).complete(key,raw);
    }
    @Test void observedMetadataCannotClaimAnotherDeployment() {
        seed(); var raw=envelope(true).replace("c".repeat(40),"f".repeat(40));
        assertThatThrownBy(()->service.result(principal,key,raw,json.readTree(raw))).isInstanceOf(OcrHttpException.class);
        verifyNoInteractions(jobs);
    }
    @Test void postPreflightResultCannotOmitMeasuredFingerprint() {
        seed(); var raw=envelope(false);
        assertThatThrownBy(()->service.result(principal,key,raw,json.readTree(raw))).isInstanceOf(OcrHttpException.class);
        verifyNoInteractions(jobs);
    }
}
