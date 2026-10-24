package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.*;
import com.cvcompass.backend.ingestion.entity.*;
import com.cvcompass.backend.ingestion.repository.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OcrEnqueueTests {
    private final OcrIntegrationProperties config=new OcrIntegrationProperties();
    private final OcrJobProperties limits=new OcrJobProperties();
    private final CvProcessingJobRepository jobs=mock(CvProcessingJobRepository.class);
    private final OcrStateRepository state=mock(OcrStateRepository.class);
    private final OcrAdmissionProperties admission = new OcrAdmissionProperties();
    private final OcrEnqueueService service=new OcrEnqueueService(config,limits,jobs,JsonMapper.builder().build(),state,
            new OcrAdmissionPolicy(admission, config));
    private CvDocument document() {
        var document=new CvDocument(); org.springframework.test.util.ReflectionTestUtils.setField(document,"id",42L);
        document.setFileSize(25L); document.setSha256("a".repeat(64));
        return document;
    }
    private void enroll() {
        config.setEnabled(true); config.setNamespace("test-only");
        var fixture=new OcrIntegrationProperties.Fixture(); fixture.setFixtureId("known-fixture");
        fixture.setSha256("a".repeat(64)); fixture.setSizeBytes(25); fixture.setProfileRevision("test-v1");
        config.getFixtures().add(fixture);
        var profile=new OcrIntegrationProperties.Profile(); profile.setWorkerCommit("b".repeat(40));
        profile.setImageId("sha256:"+"c".repeat(64)); profile.setConfigFingerprint("d".repeat(64));
        var mapper=JsonMapper.builder().build(); var engines=mapper.createObjectNode();
        for (String name:java.util.Set.of("python","pdfplumber","pdfminer_six","pypdfium2","pdfium","tesseract","leptonica","pillow","worker_version"))
            engines.put(name,"synthetic-test");
        var models=engines.putArray("models");
        for (String name:java.util.List.of("vie","eng","osd")) models.addObject().put("name",name).put("revision","a".repeat(40)).put("sha256","b".repeat(64));
        profile.setEnginesJson(engines.toString()); config.getProfiles().put("test-v1",profile);
    }
    @Test void disabledDoesNotTouchQueue() { service.enqueue(document()); verifyNoInteractions(jobs,state); }
    @Test void unknownHashOrSizeIsNotEnrolled() {
        enroll(); var document=document(); document.setSha256("e".repeat(64)); service.enqueue(document);
        document.setSha256("a".repeat(64)); document.setFileSize(26L); service.enqueue(document);
        verifyNoInteractions(jobs,state);
    }
    @Test void snapshotBindsGeneratedDocumentIdentityAndConfiguredBudget() {
        enroll(); limits.setMaxAttempts(2);
        var now=Instant.parse("2026-01-01T00:00:00Z"); when(state.wallClock()).thenReturn(now);
        service.enqueue(document());
        verify(jobs).saveAndFlush(argThat(job -> {
            assertThat(job.getMaxAttempts()).isEqualTo(2); assertThat(job.getGeneration()).isEqualTo(1);
            assertThat(job.getNextAttemptAt()).isEqualTo(now);
            assertThat(job.getAdmissionSnapshot().get("engines")).isInstanceOf(java.util.Map.class);
            assertThat(job.getAdmissionSnapshot()).containsEntry("document_id","42").containsEntry("namespace","test-only")
                    .containsEntry("sha256","a".repeat(64)).containsEntry("size_bytes",25L);
            return true;
        }));
    }
    @Test void queueFailureIsNotSwallowed() {
        enroll(); var failure=new IllegalStateException("synthetic"); doThrow(failure).when(jobs).saveAndFlush(any());
        assertThatThrownBy(()->service.enqueue(document())).isSameAs(failure);
    }
    @Test void malformedOrUnmeasuredRegistryFailsBeforeAnyJobWrite() {
        enroll();
        for (String raw:java.util.List.of("{\"models\":[]}","null","{\"python\":null}","{} {}")) {
            config.getProfiles().get("test-v1").setEnginesJson(raw);
            assertThatThrownBy(()->new OcrEnqueueService(config,limits,jobs,JsonMapper.builder().build(),state,
                    new OcrAdmissionPolicy(admission, config)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Invalid OCR deployment registry");
        }
        verifyNoInteractions(jobs,state);
    }

    @Test void localAdmissionDoesNotInventFixtureProvenance() {
        enroll();
        admission.setMode(OcrAdmissionProperties.Mode.LOCAL);
        admission.setLocalProfileRevision("test-v1");
        config.getFixtures().clear();
        var document = document();
        document.setSha256("f".repeat(64));
        service.enqueue(document);
        verify(jobs).saveAndFlush(argThat(job -> {
            assertThat(job.getProfileRevision()).isEqualTo("test-v1");
            assertThat(job.getAdmissionSnapshot()).containsEntry("admission_mode", "local")
                    .containsEntry("sha256", "f".repeat(64)).doesNotContainKey("fixture_id");
            return true;
        }));
    }
}
