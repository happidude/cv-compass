package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.DiskImportProperties;
import com.cvcompass.backend.ingestion.config.BatchProperties;
import com.cvcompass.backend.ingestion.repository.DiskImportRepository;
import com.cvcompass.backend.ingestion.source.ImportSource;
import com.cvcompass.backend.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import static org.mockito.Mockito.*;

class DiskImportServiceTests {
    @Test void shutdownDuringPreparationDoesNotStartTheQueuedBatch() {
        var source=mock(ImportSource.class); var history=mock(DiskImportRepository.class);
        var batches=mock(CvBatchService.class); var storage=mock(FileStorageService.class);
        var manager=mock(PlatformTransactionManager.class);
        var instant=java.time.Instant.parse("2030-01-01T00:00:00Z");
        var service = new DiskImportService(source, history, batches, storage, new DiskImportProperties(),
                new BatchProperties(), Clock.fixed(instant, java.time.ZoneOffset.UTC), manager);
        var file=new com.cvcompass.backend.ingestion.dto.SourceFile("a.pdf",20,"stamp","key");
        var entry=new com.cvcompass.backend.ingestion.dto.DiskImportEntry(1,file,"a".repeat(64),"PENDING",0,0,null,"b".repeat(32)+".pdf");
        when(source.key()).thenReturn("c".repeat(64));
        try { when(source.fingerprint(file)).thenReturn("a".repeat(64)); } catch(java.io.IOException impossible) { throw new AssertionError(impossible); }
        when(history.stable(anyString(),any(),any(),anyString(),anyInt(),anyInt(),any())).thenReturn(java.util.List.of(file));
        when(history.identify(anyString(),any(),anyString(),any())).thenReturn(entry);
        when(history.due(anyLong(),any(),anyInt())).thenReturn(true);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        doAnswer(call -> { service.closing(); return null; }).when(history).queue(anyList());
        service.tick();
        verify(history).queue(anyList()); verifyNoInteractions(batches);
    }
    @Test void shutdownRefusesAnyFurtherBatchOrSourceWork() {
        var source=mock(ImportSource.class); var history=mock(DiskImportRepository.class);
        var batches=mock(CvBatchService.class); var storage=mock(FileStorageService.class);
        var manager=mock(PlatformTransactionManager.class); var clock=mock(Clock.class);
        var service = new DiskImportService(source, history, batches, storage, new DiskImportProperties(),
                new BatchProperties(), clock, manager);
        service.closing(); service.stop(); service.tick();
        verifyNoInteractions(source,history,batches,storage,clock,manager);
    }
}
