package com.cvcompass.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cvcompass.backend.web.OperationalLog;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.slf4j.event.Level.*;
import static com.cvcompass.backend.web.OperationalLog.Event.*;

class OperationalLogTests {
    @Test void deferredEventSnapshotsContextAndNeverLogsOnRollback() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.cvcompass.events");
        var appender = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start(); logger.addAppender(appender);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            MDC.put("correlationId", "upload-1");
            OperationalLog.committed(INFO, OCR_ENQUEUED, null, Map.of("documentId", 42L));
            assertThat(appender.list).isEmpty();
            MDC.put("correlationId", "other-request");
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            assertThat(appender.list).hasSize(1);
            MDC.put("rawText", "PRIVATE"); MDC.put("workerId", "previous-worker");
            OperationalLog.write(WARN, OCR_TRANSPORT_FAILED, "OCR_UNAVAILABLE", Map.of("documentId", 44L));
            assertThat(appender.list.getLast().getMDCPropertyMap()).doesNotContainKeys("rawText", "workerId")
                    .containsEntry("documentId", "44").containsEntry("errorCode", "OCR_UNAVAILABLE");
            assertThat(appender.list.getLast().getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(MDC.get("rawText")).isEqualTo("PRIVATE");
            assertThat(appender.list.getFirst().getMDCPropertyMap()).containsEntry("correlationId", "upload-1")
                    .containsEntry("documentId", "42");
            assertThat(MDC.get("correlationId")).isEqualTo("other-request");
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            OperationalLog.committed(INFO, DOCUMENT_STORED, null, Map.of("documentId", 43L));
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
            assertThat(appender.list).hasSize(2);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            MDC.clear(); logger.detachAppender(appender); appender.stop();
        }
    }

    @Test void unsafeFieldsAndCodesAreRejectedAndContextRestored() {
        MDC.put("correlationId", "request-2");
        try {
            OperationalLog.write(WARN, OCR_TRANSPORT_FAILED, "secret@example.test\nINJECT",
                    Map.of("rawText", "PRIVATE CV", "workerId", "bad\nvalue"));
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("correlationId", "request-2"));
            assertThatCode(() -> OperationalLog.committed(INFO, DOCUMENT_STORED, null, Map.of())).doesNotThrowAnyException();
        } finally { MDC.clear(); }
    }
}
