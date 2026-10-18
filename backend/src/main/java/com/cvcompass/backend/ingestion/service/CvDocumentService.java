package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.dto.DocumentMetadata;
import com.cvcompass.backend.ingestion.exception.DocumentNotFoundException;
import com.cvcompass.backend.ingestion.upload.PdfUploadValidation;
import com.cvcompass.backend.ingestion.upload.UploadFilename;
import com.cvcompass.backend.ingestion.exception.UploadRejectedException;

import com.cvcompass.backend.ingestion.entity.CvDocument;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.storage.FileStorageService;
import com.cvcompass.backend.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@Service
public class CvDocumentService {
    private static final Logger log = LoggerFactory.getLogger(CvDocumentService.class);
    private final FileStorageService storage;
    private final CvDocumentRepository documents;
    private final TransactionTemplate metadataTransaction;

    public CvDocumentService(FileStorageService storage, CvDocumentRepository documents,
                             PlatformTransactionManager transactionManager) {
        this.storage = storage;
        this.documents = documents;
        this.metadataTransaction = new TransactionTemplate(transactionManager);
        // Complete our own commit before returning, even if invoked from another transaction.
        this.metadataTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Borrows content; the caller closes it. File I/O is outside the metadata transaction. */
    public DocumentMetadata upload(String originalFilename, InputStream content) throws IOException {
        return upload(originalFilename, content, document -> DocumentMetadata.from(documents.saveAndFlush(document)));
    }

    /** Persistence callback runs inside the compensated metadata transaction. */
    private <T> T upload(String originalFilename, InputStream content,
                        java.util.function.Function<CvDocument, T> persist) throws IOException {
        return upload(originalFilename, content, PdfUploadValidation.MAX_BYTES, persist);
    }

    <T> T upload(String originalFilename, InputStream content, long maxBytes,
                        java.util.function.Function<CvDocument, T> persist) throws IOException {
        String filename = UploadFilename.safeFilename(originalFilename);
        var validated = PdfUploadValidation.open(Objects.requireNonNull(content), maxBytes);
        final com.cvcompass.backend.storage.StoredFile stored;
        try {
            stored = storage.store(validated);
        } catch (StorageException failure) {
            if (failure.getCause() instanceof com.cvcompass.backend.ingestion.exception.SourceChangedException changed) throw changed;
            if (failure.getCause() instanceof UploadRejectedException rejected) {
                throw rejected;
            }
            throw failure;
        }
        try {
            return Objects.requireNonNull(metadataTransaction.execute(status -> {
                var document = new CvDocument();
                document.setOriginalFilename(filename);
                document.setStoragePath(stored.key().value());
                document.setFileSize(stored.sizeBytes());
                document.setSha256(stored.sha256());
                document.setMimeType("application/pdf");
                document.setStatus(DocumentStatus.PENDING);
                // Batch, candidate and processing job creation are explicitly outside M4.
                return persist.apply(document);
            }));
        } catch (RuntimeException | Error primary) {
            try {
                storage.delete(stored.key());
            } catch (RuntimeException cleanup) {
                if (cleanup != primary) primary.addSuppressed(cleanup);
                // Opaque key is necessary for orphan recovery; no filename, contents or cause messages.
                log.error("Orphan cleanup required storageKey={} cleanupType={}",
                        stored.key().value(), cleanup.getClass().getName());
            }
            throw primary;
        }
    }

    @Transactional(readOnly = true)
    public DocumentMetadata find(long id) {
        return documents.findById(id).map(DocumentMetadata::from)
                .orElseThrow(DocumentNotFoundException::new);
    }

}
