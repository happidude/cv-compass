package com.cvcompass.backend.ingestion.exception;

import com.cvcompass.backend.ingestion.controller.CvDocumentController;
import com.cvcompass.backend.ingestion.config.UploadProperties;
import com.cvcompass.backend.ingestion.dto.DocumentCursorPage;
import com.cvcompass.backend.ingestion.dto.DocumentDetails;
import com.cvcompass.backend.ingestion.dto.DocumentMetadata;
import com.cvcompass.backend.ingestion.dto.DocumentOcrResult;
import com.cvcompass.backend.ingestion.dto.DocumentProcessing;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.service.CvDocumentService;
import com.cvcompass.backend.ingestion.service.DocumentQueryService;
import com.cvcompass.backend.web.error.GlobalExceptionHandler;
import com.cvcompass.backend.web.logging.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MVC boundary only; uses the real application error advice and correlation filter. */
class CvDocumentQueryHttpTests {
    private final CvDocumentService uploads = mock(CvDocumentService.class);
    private final DocumentQueryService queries = mock(DocumentQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CvDocumentController(
                    uploads, queries, new UploadProperties()))
            .setControllerAdvice(new IngestionExceptionHandler(), new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter()).build();

    @Test
    void uploadReturnsServiceMetadataWithoutQueryingCommittedDocument() throws Exception {
        var metadata = new DocumentMetadata(7L, "synthetic.pdf", 3, "application/pdf",
                "a".repeat(64), DocumentStatus.PENDING, Instant.EPOCH);
        when(uploads.upload(eq("synthetic.pdf"), any(InputStream.class))).thenReturn(metadata);
        byte[] content = {1, 2, 3};
        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "synthetic.pdf", "application/pdf", content))
                        .with(request -> {
                            request.addPart(new MockPart("file", "synthetic.pdf", content));
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/documents/7"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.originalFilename").value("synthetic.pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(3))
                .andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.sha256").value(metadata.sha256()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value(Instant.EPOCH.toString()))
                .andExpect(jsonPath("$.processing").doesNotExist())
                .andExpect(jsonPath("$.ocr").doesNotExist());
        verify(uploads).upload(eq("synthetic.pdf"), any(InputStream.class));
        verifyNoInteractions(queries);
    }

    @Test
    void listBindsCursorAndReturnsExistingUnwrappedResponseConvention() throws Exception {
        when(queries.list(42L, 2)).thenReturn(new DocumentCursorPage(List.of(), 2, false, null));
        mvc.perform(get("/api/documents?beforeId=42&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
        verify(queries).list(42L, 2);
    }

    @Test
    void malformedAndOutOfRangeParametersUseApiError() throws Exception {
        for (String query : List.of("size=nope", "beforeId=nope", "size=0", "beforeId=-1", "size=2147483648")) {
            mvc.perform(get("/api/documents?" + query).header("X-Correlation-ID", "m8-query"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.correlationId").value("m8-query"))
                    .andExpect(jsonPath("$.details").isArray());
        }
    }

    @Test
    void configuredMaximumUsesSafeBadRequestContract() throws Exception {
        when(queries.list(null, 4)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        mvc.perform(get("/api/documents?size=4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void detailDelegatesAndRejectsNonPositiveId() throws Exception {
        var processing = new DocumentProcessing(null, "NOT_SCHEDULED", null, null, null, null, null,
                null, null, "NONE");
        var result = new DocumentOcrResult("NONE", null, null, null, List.of(), List.of(), List.of(), null);
        when(queries.find(7)).thenReturn(new DocumentDetails(7L, "synthetic.pdf", 100, "application/pdf",
                "a".repeat(64), DocumentStatus.PENDING, Instant.EPOCH, null, "STORED", processing, result));
        mvc.perform(get("/api/documents/0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.originalFilename").value("synthetic.pdf"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.ingestionStatus").value("STORED"))
                .andExpect(jsonPath("$.processing.status").value("NOT_SCHEDULED"))
                .andExpect(jsonPath("$.ocr.availability").value("NONE"))
                .andExpect(jsonPath("$.ocr.pages").isEmpty())
                .andExpect(jsonPath("$.storagePath").doesNotExist());
        verify(queries).find(7);
    }

    @Test
    void missingDocumentUsesExisting404Convention() throws Exception {
        when(queries.find(8)).thenThrow(new DocumentNotFoundException());
        mvc.perform(get("/api/documents/8").header("X-Correlation-ID", "m8-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("m8-missing"));
    }
}
