package com.cvcompass.backend.web;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Safe lifecycle events shared by modules. Never pass payloads or exception messages. */
public final class OperationalLog {
    private OperationalLog() {}

    public enum Event {
        DOCUMENT_STORED,
        OCR_ENQUEUED,
        OCR_NOT_ENQUEUED,
        OCR_CLAIMED,
        OCR_RESULT_RECEIVED,
        OCR_EXTRACTION_STORED,
        OCR_STATE_CHANGED,
        OCR_LEASE_EXPIRED,
        OCR_RETRY_SCHEDULED,
        OCR_TERMINAL_FAILURE,
        OCR_POLL_EMPTY,
        OCR_HEARTBEAT,
        OCR_REAPER_FAILED,
        STORAGE_CLEANUP_FAILED,
        HTTP_REQUEST_FAILED,
        OCR_TRANSPORT_FAILED,
        LOG_TRANSACTION_UNAVAILABLE,
        MAPPING_CLAIMED,
        MAPPING_COMPLETED,
        MAPPING_ATTEMPT_FAILED,
        MAPPING_SCHEDULER_FAILED
    }

    private static final Set<String> KEYS =
            Set.of(
                    "documentId",
                    "jobId",
                    "attemptId",
                    "workerId",
                    "extractionId",
                    "status",
                    "exceptionType",
                    "storageKey");

    public static void write(Level level, Event event, String errorCode, Map<String, ?> context) {
        Map<String, String> before = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            if (before != null && before.get("correlationId") != null)
                MDC.put("correlationId", before.get("correlationId"));
            MDC.put("event", event.name());
            MDC.put(
                    "errorCode",
                    errorCode == null
                            ? "NONE"
                            : errorCode.matches("[A-Z][A-Z0-9_]{0,63}")
                                    ? errorCode
                                    : "INVALID_LOG_CODE");
            context.forEach(
                    (key, value) -> {
                        if (key.equals("applicationStack")
                                && value instanceof SafeStackLocations locations) {
                            MDC.put(key, locations.value());
                            return;
                        }
                        if (KEYS.contains(key) && value != null) {
                            String safe = value.toString();
                            String pattern =
                                    key.equals("storageKey")
                                            ? "[A-Za-z0-9][A-Za-z0-9/_.-]{0,255}"
                                            : "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}";
                            if (safe.matches(pattern)) MDC.put(key, safe);
                        }
                    });
            LoggerFactory.getLogger("com.cvcompass.events")
                    .atLevel(level)
                    .log(
                            event.name()
                                    + (MDC.get("storageKey") == null
                                            ? ""
                                            : " storageKey=" + MDC.get("storageKey"))
                                    + (MDC.get("exceptionType") == null
                                            ? ""
                                            : " exceptionType=" + MDC.get("exceptionType")));
        } finally {
            MDC.clear();
            if (before != null) MDC.setContextMap(before);
        }
    }

    /** Snapshot now: deferred events must not read another scope's MDC or mutable entities. */
    public static void committed(Level level, Event event, String code, Map<String, ?> context) {
        var snapshot = new HashMap<>(context);
        String correlation = MDC.get("correlationId");
        Runnable emit =
                () -> {
                    Map<String, String> before = MDC.getCopyOfContextMap();
                    try {
                        MDC.clear();
                        if (correlation != null) MDC.put("correlationId", correlation);
                        write(level, event, code, snapshot);
                    } finally {
                        MDC.clear();
                        if (before != null) MDC.setContextMap(before);
                    }
                };
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            write(Level.WARN, Event.LOG_TRANSACTION_UNAVAILABLE, "NO_TRANSACTION", Map.of());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        emit.run();
                    }
                });
    }
}
