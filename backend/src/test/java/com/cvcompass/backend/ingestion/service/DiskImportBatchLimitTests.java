package com.cvcompass.backend.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cvcompass.backend.ingestion.config.BatchProperties;
import com.cvcompass.backend.ingestion.config.DiskImportProperties;
import com.cvcompass.backend.ingestion.dto.BatchItem;
import com.cvcompass.backend.ingestion.dto.DiskImportEntry;
import com.cvcompass.backend.ingestion.dto.ImportFile;
import com.cvcompass.backend.ingestion.dto.SourceFile;
import com.cvcompass.backend.ingestion.repository.DiskImportRepository;
import com.cvcompass.backend.ingestion.source.ImportSource;
import com.cvcompass.backend.storage.FileStorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Orchestration regression with in-memory history doubles, not PostgreSQL evidence. */
class DiskImportBatchLimitTests {
    @Test
    void largerImportSizeDrainsFiveFiveAndTwoWithoutStrandingQueuedFiles() throws Exception {
        drainsInGroups(10, 5, List.of(5, 5, 2));
    }

    @Test
    void smallerImportSizeStillControlsTheGroups() throws Exception {
        drainsInGroups(3, 5, List.of(3, 3, 3, 3));
    }

    private void drainsInGroups(int importSize, int maximum, List<Integer> expectedGroups)
            throws Exception {
        var source = mock(ImportSource.class);
        var history = mock(DiskImportRepository.class);
        var batches = mock(CvBatchService.class);
        var storage = mock(FileStorageService.class);
        var manager = mock(PlatformTransactionManager.class);
        var properties = new DiskImportProperties();
        properties.setBatchSize(importSize);
        var batchProperties = new BatchProperties();
        batchProperties.setMaxFiles(maximum);
        var clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        var service =
                new DiskImportService(
                        source,
                        history,
                        batches,
                        storage,
                        properties,
                        batchProperties,
                        clock,
                        manager);

        Map<String, DiskImportEntry> inventory = new LinkedHashMap<>();
        Map<Long, String> states = new HashMap<>();
        List<Integer> queuedGroups = new ArrayList<>();
        List<Integer> uploadedGroups = new ArrayList<>();
        List<String> uploadedNames = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            String name = "synthetic-%02d.pdf".formatted(index);
            var file = new SourceFile(name, 20, "stamp", "key");
            inventory.put(
                    name,
                    new DiskImportEntry(
                            index, file, "a".repeat(64), "PENDING", 0, 0, null, "target-" + index));
        }
        when(source.key()).thenReturn("source");
        when(source.fingerprint(any())).thenReturn("a".repeat(64));
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(history.stable(anyString(), any(), any(), anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(
                        call -> {
                            String after = call.getArgument(3);
                            int limit = call.getArgument(4);
                            return inventory.values().stream()
                                    .filter(entry -> entry.file().name().compareTo(after) > 0)
                                    .filter(entry -> !states.containsKey(entry.id()))
                                    .limit(limit)
                                    .map(DiskImportEntry::file)
                                    .toList();
                        });
        when(history.identify(anyString(), any(), anyString(), any()))
                .thenAnswer(call -> inventory.get(((SourceFile) call.getArgument(1)).name()));
        when(history.due(anyLong(), any(), anyInt())).thenReturn(true);
        doAnswer(
                        call -> {
                            List<DiskImportEntry> entries = call.getArgument(0);
                            queuedGroups.add(entries.size());
                            entries.forEach(entry -> states.put(entry.id(), "QUEUED"));
                            return null;
                        })
                .when(history)
                .queue(anyList());
        when(batches.upload(anyList(), any()))
                .thenAnswer(
                        call -> {
                            List<ImportFile> files = call.getArgument(0);
                            // Model the real batch boundary: rejection here would leave already
                            // queued files.
                            if (files.size() > batchProperties.getMaxFiles()) {
                                throw new IllegalArgumentException("Batch maximum exceeded");
                            }
                            uploadedGroups.add(files.size());
                            BatchImportObserver observer = call.getArgument(1);
                            long batchId = uploadedGroups.size();
                            observer.created(batchId);
                            for (int index = 0; index < files.size(); index++) {
                                var file = files.get(index);
                                uploadedNames.add(file.name());
                                observer.started(index + 1, batchId);
                                observer.recorded(
                                        index + 1,
                                        new BatchItem(
                                                index + 1,
                                                file.name(),
                                                inventory.get(file.name()).id(),
                                                null));
                            }
                            return null;
                        });
        doAnswer(
                        call -> {
                            states.put(call.getArgument(0), "SUCCEEDED_MOVE");
                            return null;
                        })
                .when(history)
                .outcome(anyLong(), any(), anyInt(), any());
        when(history.moves(anyString(), any(), anyLong(), anyInt()))
                .thenAnswer(
                        call -> {
                            long after = call.getArgument(2);
                            int limit = call.getArgument(3);
                            return inventory.values().stream()
                                    .filter(entry -> entry.id() > after)
                                    .filter(
                                            entry ->
                                                    "SUCCEEDED_MOVE".equals(states.get(entry.id())))
                                    .limit(limit)
                                    .toList();
                        });
        doAnswer(
                        call -> {
                            DiskImportEntry entry = call.getArgument(0);
                            states.put(entry.id(), "PROCESSED");
                            return null;
                        })
                .when(history)
                .moved(any());

        service.tick();

        assertThat(queuedGroups).containsExactlyElementsOf(expectedGroups);
        assertThat(uploadedGroups).containsExactlyElementsOf(expectedGroups);
        assertThat(uploadedNames).containsExactlyElementsOf(inventory.keySet());
        assertThat(states).hasSize(inventory.size());
        assertThat(states.values()).containsOnly("PROCESSED");

        service.tick();

        assertThat(uploadedGroups).containsExactlyElementsOf(expectedGroups);
        assertThat(uploadedNames).hasSize(inventory.size()).doesNotHaveDuplicates();
        assertThat(states.values()).containsOnly("PROCESSED");
    }
}
