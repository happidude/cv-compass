package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.dto.BatchDetails;
import com.cvcompass.backend.ingestion.config.BatchProperties;
import com.cvcompass.backend.ingestion.dto.BatchItem;
import com.cvcompass.backend.ingestion.exception.BatchNotFoundException;
import com.cvcompass.backend.ingestion.dto.ImportResult;
import com.cvcompass.backend.ingestion.dto.PageResult;
import com.cvcompass.backend.ingestion.exception.UploadRejectedException;

import com.cvcompass.backend.ingestion.entity.*;
import com.cvcompass.backend.ingestion.enums.BatchStatus;
import com.cvcompass.backend.ingestion.repository.*;
import com.cvcompass.backend.ingestion.upload.*;
import com.cvcompass.backend.storage.StorageException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.cvcompass.backend.ingestion.dto.ImportFile;
import com.cvcompass.backend.ingestion.exception.SourceChangedException;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Service
public class CvBatchService {
    private final CvImportBatchRepository batches;
    private final CvImportItemRepository items;
    private final CvDocumentRepository documents;
    private final CvDocumentService uploads;
    private final TransactionTemplate transaction;
    private final long maxFileBytes;
    private final int maxFiles;

    public CvBatchService(CvImportBatchRepository batches, CvImportItemRepository items,
                          CvDocumentRepository documents, CvDocumentService uploads,
                          PlatformTransactionManager manager,
                          BatchProperties properties) {
        this.maxFileBytes = properties.getMaxFileBytes();
        this.maxFiles = properties.getMaxFiles();
        this.batches = batches; this.items = items; this.documents = documents; this.uploads = uploads;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ImportResult upload(List<ImportFile> files) {
        return upload(files, new BatchImportObserver() {
            public void started(int order, long batchId) { }
            public void recorded(int order, BatchItem result) { }
        });
    }

    public ImportResult upload(List<ImportFile> files, BatchImportObserver observer) {
        if (files.isEmpty() || files.size() > maxFiles) throw new IllegalArgumentException("Invalid file count");
        long id = Objects.requireNonNull(transaction.execute(status -> {
            var batch = new CvImportBatch();
            batch.setTotalFiles(files.size()); batch.setStatus(BatchStatus.PROCESSING);
            long batchId = batches.saveAndFlush(batch).getId();
            observer.created(batchId);
            return batchId;
        }));
        var results = new ArrayList<BatchItem>();
        for (int index = 0; index < files.size(); index++) {
            var file = files.get(index);
            int order = index + 1;
            BatchItem result;
            String name;
            try { name = UploadFilename.safeFilename(file.name()); }
            catch (UploadRejectedException ignored) { name = "file-" + order; }
            final String display = name;
            observer.started(order, id);
            try {
                if (file.size() > maxFileBytes) {
                    throw new UploadRejectedException(UploadRejectedException.Reason.FILE_TOO_LARGE, "File exceeds limit");
                }
                // The producer supplies a borrowed stream; process one file at a time.
                // Upload borrows the stream and performs the compensated transaction itself.
                var stream = file.opener().open();
                try {
                    result = uploads.upload(file.name(), stream, maxFileBytes, document -> {
                        if (file.expectedSha256() != null && !file.expectedSha256().equals(document.getSha256())) throw new SourceChangedException();
                        var batch = batches.findById(id).orElseThrow(BatchNotFoundException::new);
                        document.setBatch(batch);
                        documents.saveAndFlush(document);
                        var item = record(batch, order, display, document, null);
                        observer.recorded(order, item);
                        return item;
                    });
                } finally {
                    try { stream.close(); } catch (IOException cleanup) {
                        org.slf4j.LoggerFactory.getLogger(CvBatchService.class).warn("Import stream close failed type={}", cleanup.getClass().getName());
                    }
                }
            } catch (SourceChangedException changed) {
                result = failure(id, order, display, "SOURCE_CHANGED", observer);
            } catch (UploadRejectedException rejected) {
                result = failure(id, order, display, rejected.reason().name(), observer);
            } catch (StorageException failure) {
                if (failure.reason() == StorageException.Reason.UNAVAILABLE) throw failure;
                observer.storageFailed(file.size());
                result = failure(id, order, display, "FILE_STORAGE_ERROR", observer);
            } catch (IOException failure) {
                result = failure(id, order, display, "FILE_STORAGE_ERROR", observer);
            }
            // DB and transaction errors intentionally escape: no reliable result can be recorded.
            results.add(result);
        }
        return new ImportResult(find(id), List.copyOf(results));
    }

    private BatchItem failure(long id, int order, String name, String code, BatchImportObserver observer) {
        return Objects.requireNonNull(transaction.execute(status ->
                { var item = record(batches.findById(id).orElseThrow(BatchNotFoundException::new), order, name, null, code);
                  observer.recorded(order, item); return item; }));
    }

    private BatchItem record(CvImportBatch batch, int order, String name, CvDocument document, String code) {
        var item = new CvImportItem();
        item.setBatch(batch); item.setFileOrder(order); item.setDisplayName(name);
        item.setDocument(document); item.setErrorCode(code);
        items.saveAndFlush(item);
        if (document == null) batch.setFailedCount(batch.getFailedCount() + 1);
        else batch.setSuccessCount(batch.getSuccessCount() + 1);
        if (batch.getSuccessCount() + batch.getFailedCount() == batch.getTotalFiles()) {
            batch.setCompletedAt(Instant.now());
            batch.setStatus(batch.getFailedCount() == 0 ? BatchStatus.COMPLETED
                    : batch.getSuccessCount() == 0 ? BatchStatus.FAILED : BatchStatus.PARTIAL_FAILED);
        }
        batches.saveAndFlush(batch);
        return BatchItem.from(item);
    }

    @Transactional(readOnly = true)
    public BatchDetails find(long id) {
        return batches.findById(id).map(BatchDetails::from).orElseThrow(BatchNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public PageResult<BatchDetails> list(int page, int size) {
        return PageResult.from(batches.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(BatchDetails::from));
    }

    @Transactional(readOnly = true)
    public PageResult<BatchItem> items(long id, int page, int size) {
        if (!batches.existsById(id)) throw new BatchNotFoundException();
        return PageResult.from(items.findByBatchIdOrderByFileOrderAsc(id, PageRequest.of(page, size)).map(BatchItem::from));
    }
}
