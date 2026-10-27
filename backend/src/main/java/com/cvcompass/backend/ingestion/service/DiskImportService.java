package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.BatchProperties;
import com.cvcompass.backend.ingestion.config.DiskImportProperties;
import com.cvcompass.backend.ingestion.dto.BatchItem;
import com.cvcompass.backend.ingestion.dto.DiskImportEntry;
import com.cvcompass.backend.ingestion.dto.ImportFile;
import com.cvcompass.backend.ingestion.dto.SourceFile;
import com.cvcompass.backend.ingestion.exception.SourceChangedException;
import com.cvcompass.backend.ingestion.repository.DiskImportRepository;
import com.cvcompass.backend.ingestion.source.ImportSource;
import com.cvcompass.backend.storage.FileStorageService;
import com.cvcompass.backend.storage.StorageKey;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "cv.import", name = "enabled", havingValue = "true")
public class DiskImportService {
    private static final Logger log = LoggerFactory.getLogger(DiskImportService.class);
    private final ImportSource source;
    private final DiskImportRepository history;
    private final CvBatchService batches;
    private final FileStorageService storage;
    private final DiskImportProperties properties;
    private final int importBatchSize;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final AtomicBoolean active = new AtomicBoolean();
    private final Object monitor = new Object();
    private volatile boolean stopping;

    public DiskImportService(
            ImportSource source,
            DiskImportRepository history,
            CvBatchService batches,
            FileStorageService storage,
            DiskImportProperties properties,
            BatchProperties batchProperties,
            @Qualifier("diskImportClock") Clock clock,
            PlatformTransactionManager manager) {
        this.source = source;
        this.history = history;
        this.batches = batches;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.importBatchSize = Math.min(properties.getBatchSize(), batchProperties.getMaxFiles());
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void tick() {
        if (stopping || !active.compareAndSet(false, true)) return;
        try {
            synchronized (monitor) {
                if (!stopping) run();
            }
        } catch (IOException | RuntimeException failure) {
            // Do not print source names, paths, SQL, causes or CV contents.
            log.warn("Automatic import turn stopped type={}", failure.getClass().getName());
        } finally {
            active.set(false);
        }
    }

    private void run() throws IOException {
        storage.verifyAvailable();
        String key = source.key();
        history.interrupted(
                key,
                clock.instant().plus(properties.getRetryBackoff()),
                properties.getMaxAttempts());
        movePending();
        UUID scan = UUID.randomUUID();
        source.scan(
                file -> {
                    if (!stopping) history.observe(key, file, scan, clock.instant());
                });
        String after = "";
        while (!stopping) {
            List<SourceFile> candidates =
                    history.stable(
                            key,
                            scan,
                            clock.instant().minus(properties.getStabilityInterval()),
                            after,
                            importBatchSize,
                            properties.getMaxAttempts(),
                            clock.instant());
            if (candidates.isEmpty()) break;
            after = candidates.getLast().name();
            var entries = new ArrayList<DiskImportEntry>();
            var inputs = new ArrayList<ImportFile>();
            for (var file : candidates) {
                if (stopping) break;
                try {
                    String hash =
                            source.fingerprint(file); // No DB transaction while hashing/reading.
                    var entry = history.identify(key, file, hash, clock.instant());
                    if (!history.due(entry.id(), clock.instant(), properties.getMaxAttempts()))
                        continue;
                    entries.add(entry);
                    inputs.add(
                            new ImportFile(
                                    file.name(), file.size(), () -> source.open(file), hash));
                } catch (SourceChangedException | NoSuchFileException changed) {
                    // A new observation interval is required; this is not an import attempt.
                } catch (IOException unreadable) {
                    history.readFailed(
                            key,
                            file.name(),
                            clock.instant().plus(properties.getRetryBackoff()),
                            properties.getMaxAttempts());
                }
            }
            if (!inputs.isEmpty() && !stopping) {
                transaction.executeWithoutResult(status -> history.queue(entries));
                if (stopping) return;
                batches.upload(
                        inputs,
                        new BatchImportObserver() {
                            @Override
                            public void created(long batch) {
                                history.attached(entries, batch);
                            }

                            @Override
                            public void storageFailed(long bytes) {
                                storage.verifyAvailable(bytes);
                            }

                            @Override
                            public void started(int order, long batch) {
                                var entry = entries.get(order - 1);
                                transaction.executeWithoutResult(
                                        status ->
                                                history.started(
                                                        entry.id(),
                                                        candidates.stream()
                                                                .filter(
                                                                        file ->
                                                                                file.name()
                                                                                        .equals(
                                                                                                entry.file()
                                                                                                        .name()))
                                                                .findFirst()
                                                                .orElseThrow(),
                                                        batch));
                            }

                            @Override
                            public void recorded(int order, BatchItem result) {
                                // Joins the exact transaction saving document/item/counter; never
                                // commit separately.
                                long id = entries.get(order - 1).id();
                                history.outcome(
                                        id,
                                        result,
                                        properties.getMaxAttempts(),
                                        next(history.attempts(id)));
                            }
                        });
                movePending();
            }
        }
    }

    private Instant next(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 10);
        return clock.instant().plus(properties.getRetryBackoff().multipliedBy(multiplier));
    }

    private void movePending() throws IOException {
        long after = 0;
        while (!stopping) {
            var entries =
                    history.moves(source.key(), clock.instant(), after, properties.getBatchSize());
            if (entries.isEmpty()) return;
            after = entries.getLast().id();
            for (var entry : entries) {
                if (stopping) return;
                if (entry.moveAttempts() >= properties.getMaxAttempts()) {
                    history.attention(entry.id(), "MOVE_RETRIES_EXHAUSTED");
                    continue;
                }
                if (entry.documentId() != null
                        && !storage.exists(
                                new StorageKey(history.storageKey(entry.documentId())))) {
                    history.attention(entry.id(), "STORED_FILE_MISSING");
                    continue;
                }
                history.moveStarted(entry.id());
                try {
                    source.move(
                            entry.file(),
                            entry.sha256(),
                            entry.documentId() != null,
                            entry.target());
                    history.moved(entry);
                } catch (SourceChangedException changed) {
                    history.attention(entry.id(), "SOURCE_CHANGED");
                } catch (IOException failure) {
                    history.moveFailed(entry.id(), next(entry.moveAttempts() + 1));
                    log.warn("Automatic import move deferred historyId={}", entry.id());
                }
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void closing() {
        stopping = true;
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        synchronized (monitor) {
            /* Finish the current batch before releasing source/DB. */
        }
    }
}
