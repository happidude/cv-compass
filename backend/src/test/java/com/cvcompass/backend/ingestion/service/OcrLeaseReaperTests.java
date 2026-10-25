package com.cvcompass.backend.ingestion.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class OcrLeaseReaperTests {
    @Test void delegatesToExistingTransactionalStateMachine() {
        var jobs=mock(OcrJobService.class); new OcrLeaseReaper(jobs).expire(); verify(jobs).expireDue();
    }
    @Test void failedTurnIsReportedSafelyAndDoesNotDisableLaterTurns(CapturedOutput output) {
        var jobs=mock(OcrJobService.class);
        when(jobs.expireDue()).thenThrow(new IllegalStateException("synthetic-private-sql-detail")).thenReturn(0);
        var reaper=new OcrLeaseReaper(jobs); reaper.expire(); reaper.expire();
        verify(jobs,times(2)).expireDue();
        assertThat(output.getOut()).contains("OCR_REAPER_FAILED", "exceptionType=java.lang.IllegalStateException")
                .doesNotContain("synthetic-private-sql-detail");
    }
    @Test void reaperBeanIsNotRegisteredWhenIntegrationIsDisabled() {
        new ApplicationContextRunner().withBean(OcrJobService.class,()->mock(OcrJobService.class))
                .withUserConfiguration(OcrLeaseReaper.class)
                .run(context->assertThat(context).doesNotHaveBean(OcrLeaseReaper.class));
    }
}
