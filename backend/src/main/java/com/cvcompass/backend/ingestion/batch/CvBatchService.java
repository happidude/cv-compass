package com.cvcompass.backend.ingestion.batch;

import com.cvcompass.backend.ingestion.entity.*;
import com.cvcompass.backend.ingestion.enums.BatchStatus;
import com.cvcompass.backend.ingestion.repository.*;
import com.cvcompass.backend.ingestion.upload.*;
import com.cvcompass.backend.storage.StorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
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

    public CvBatchService(CvImportBatchRepository batches, CvImportItemRepository items,
                          CvDocumentRepository documents, CvDocumentService uploads,
                          PlatformTransactionManager manager,
                          @Value("${cv.batch.max-file-bytes:10485760}") long maxFileBytes) {
        if (maxFileBytes < 1 || maxFileBytes > 10485760) throw new IllegalArgumentException("Invalid batch file limit");
        this.maxFileBytes = maxFileBytes;
        this.batches = batches; this.items = items; this.documents = documents; this.uploads = uploads;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record ImportResult(BatchDetails batch, List<BatchItem> items) { }

    public ImportResult upload(List<MultipartFile> files) {
        long id = Objects.requireNonNull(transaction.execute(status -> {
            var batch = new CvImportBatch();
            batch.setTotalFiles(files.size()); batch.setStatus(BatchStatus.PROCESSING);
            return batches.saveAndFlush(batch).getId();
        }));
        var results = new ArrayList<BatchItem>();
        for (int index = 0; index < files.size(); index++) {
            var file = files.get(index);
            int order = index + 1;
            BatchItem result;
            String name;
            try { name = CvDocumentService.safeFilename(file.getOriginalFilename()); }
            catch (UploadRejectedException ignored) { name = "file-" + order; }
            final String display = name;
            try {
                if (file.getSize() > maxFileBytes) {
                    throw new UploadRejectedException(UploadRejectedException.Reason.FILE_TOO_LARGE, "File exceeds limit");
                }
                // Multipart content is spooled by the container; upload streams one file at a time.
                // Upload borrows the stream and performs the compensated transaction itself.
                var stream = file.getInputStream();
                try {
                    result = uploads.upload(file.getOriginalFilename(), stream, maxFileBytes, document -> {
                        var batch = batches.findById(id).orElseThrow(BatchNotFoundException::new);
                        document.setBatch(batch);
                        documents.saveAndFlush(document);
                        return record(batch, order, display, document, null);
                    });
                } finally {
                    try { stream.close(); } catch (IOException cleanup) {
                        org.slf4j.LoggerFactory.getLogger(CvBatchService.class).warn("Multipart stream close failed type={}", cleanup.getClass().getName());
                    }
                }
            } catch (UploadRejectedException rejected) {
                result = failure(id, order, display, rejected.reason().name());
            } catch (StorageException failure) {
                if (failure.reason() == StorageException.Reason.UNAVAILABLE) throw failure;
                result = failure(id, order, display, "FILE_STORAGE_ERROR");
            } catch (IOException failure) {
                result = failure(id, order, display, "FILE_STORAGE_ERROR");
            }
            // DB and transaction errors intentionally escape: no reliable result can be recorded.
            results.add(result);
        }
        return new ImportResult(find(id), List.copyOf(results));
    }

    private BatchItem failure(long id, int order, String name, String code) {
        return Objects.requireNonNull(transaction.execute(status ->
                record(batches.findById(id).orElseThrow(BatchNotFoundException::new), order, name, null, code)));
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
