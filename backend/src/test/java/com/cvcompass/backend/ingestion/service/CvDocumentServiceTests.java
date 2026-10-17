package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.exception.DocumentNotFoundException;
import com.cvcompass.backend.ingestion.exception.UploadRejectedException;

import com.cvcompass.backend.ingestion.repository.CvDocumentRepository;
import com.cvcompass.backend.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class CvDocumentServiceTests {
    private final FileStorageService storage = mock(FileStorageService.class);
    private final CvDocumentRepository documents = mock(CvDocumentRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final StoredFile stored = new StoredFile(new StorageKey("a".repeat(32)), 23, "b".repeat(64));
    private final CvDocumentService service = new CvDocumentService(storage, documents, transactions);

    @Test
    void storageFailureNeverTouchesDatabase() {
        var failure = new StorageException(StorageException.Reason.WRITE_FAILED, "Synthetic storage failure");
        when(storage.store(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.upload("fixture.pdf", pdf())).isSameAs(failure);
        verifyNoInteractions(documents, transactions);
    }

    @Test
    void databaseFailureDeletesFileAndPreservesPrimary() {
        var failure = new IllegalStateException("synthetic database error");
        prepareTransaction();
        when(documents.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.upload("fixture.pdf", pdf())).isSameAs(failure);
        verify(storage).delete(stored.key());
        verify(transactions).rollback(any());
    }

    @Test
    void commitFailureAlsoDeletesFile() {
        var failure = new IllegalStateException("synthetic commit error");
        prepareTransaction();
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(failure).when(transactions).commit(any());
        assertThatThrownBy(() -> service.upload("fixture.pdf", pdf())).isSameAs(failure);
        verify(storage).delete(stored.key());
        verify(transactions).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void cleanupFailureDoesNotHidePrimaryAndLogsOnlyRecoveryKey(CapturedOutput output) {
        var failure = new IllegalStateException("synthetic-private-database-detail");
        var cleanup = new StorageException(StorageException.Reason.DELETE_FAILED, "synthetic-private-filesystem-detail");
        prepareTransaction();
        when(documents.saveAndFlush(any())).thenThrow(failure);
        doThrow(cleanup).when(storage).delete(stored.key());
        assertThatThrownBy(() -> service.upload("synthetic-private-name.pdf", pdf()))
                .isSameAs(failure).satisfies(error -> assertThat(error.getSuppressed()).containsExactly(cleanup));
        assertThat(output.getOut()).contains("Orphan cleanup required", stored.key().value())
                .doesNotContain("synthetic-private-database-detail", "synthetic-private-filesystem-detail",
                        "synthetic-private-name");
    }

    @Test
    void usesActualStorageMetadataAndSanitizesDisplayFilename() throws Exception {
        prepareTransaction();
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> {
            var entity = (com.cvcompass.backend.ingestion.entity.CvDocument) invocation.getArgument(0);
            assertThat(entity.getBatch()).isNull();
            assertThat(entity.getCandidate()).isNull();
            assertThat(entity.getOriginalFilename()).isEqualTo("fixture.pdf");
            assertThat(entity.getStoragePath()).isEqualTo(stored.key().value());
            assertThat(entity.getFileSize()).isEqualTo(stored.sizeBytes());
            assertThat(entity.getSha256()).isEqualTo(stored.sha256());
            assertThat(entity.getStatus()).isEqualTo(com.cvcompass.backend.ingestion.enums.DocumentStatus.PENDING);
            return entity;
        });
        var result = service.upload("C:\\client\\fixture.pdf", pdf());
        assertThat(result.sizeBytes()).isEqualTo(stored.sizeBytes());
        verify(transactions).commit(any());
        verify(storage, never()).delete(any());
    }

    @Test
    void validationFromStorageIsPreserved() {
        var validation = new UploadRejectedException(UploadRejectedException.Reason.FILE_TOO_LARGE, "File too large");
        when(storage.store(any())).thenThrow(new StorageException(StorageException.Reason.WRITE_FAILED,
                "Could not store file", validation));
        assertThatThrownBy(() -> service.upload("fixture.pdf", pdf())).isSameAs(validation);
        verifyNoInteractions(documents, transactions);
    }

    @Test
    void unsafeFilenameIsRejectedBeforeStorage() {
        assertThatThrownBy(() -> service.upload("unsafe\r\nname.pdf", pdf()))
                .isInstanceOf(UploadRejectedException.class);
        verifyNoInteractions(storage, documents, transactions);
    }

    @Test
    void missingDocumentHasDomainNotFoundFailure() {
        when(documents.findById(99L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.find(99L)).isInstanceOf(DocumentNotFoundException.class);
    }

    private void prepareTransaction() {
        when(storage.store(any())).thenReturn(stored);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private static ByteArrayInputStream pdf() {
        return new ByteArrayInputStream("%PDF-1.7\nsynthetic\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
    }
}
