package com.cvcompass.backend.ingestion.exception;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cvcompass.backend.ingestion.config.BatchProperties;
import com.cvcompass.backend.ingestion.config.PaginationProperties;
import com.cvcompass.backend.ingestion.controller.CvBatchController;
import com.cvcompass.backend.ingestion.service.CvBatchService;
import com.cvcompass.backend.web.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CvBatchConfiguredHttpTests {
    private final CvBatchService service = mock(CvBatchService.class);

    private MockMvc mvc() {
        var batch = new BatchProperties();
        batch.setMaxFiles(1);
        var pagination = new PaginationProperties();
        pagination.setDefaultPageSize(2);
        pagination.setMaxPageSize(3);
        return MockMvcBuilders.standaloneSetup(new CvBatchController(service, batch, pagination))
                .setControllerAdvice(new IngestionExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void existingBatchAndItemRoutesUseConfiguredDefaultSize() throws Exception {
        var mvc = mvc();
        mvc.perform(get("/api/batches")).andExpect(status().isOk());
        mvc.perform(get("/api/batches/7/items")).andExpect(status().isOk());
        verify(service).list(0, 2);
        verify(service).items(7, 0, 2);
    }

    @Test
    void configuredMaximumAndNegativePageAreRejected() throws Exception {
        var mvc = mvc();
        mvc.perform(get("/api/batches?size=4")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/batches/7/items?size=4")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/batches?page=-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void configuredBatchCountIsEnforcedBeforeIngestion() throws Exception {
        mvc().perform(
                        multipart("/api/batches")
                                .file(
                                        new MockMultipartFile(
                                                "files",
                                                "synthetic-a.pdf",
                                                "application/pdf",
                                                new byte[] {1}))
                                .file(
                                        new MockMultipartFile(
                                                "files",
                                                "synthetic-b.pdf",
                                                "application/pdf",
                                                new byte[] {2})))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
