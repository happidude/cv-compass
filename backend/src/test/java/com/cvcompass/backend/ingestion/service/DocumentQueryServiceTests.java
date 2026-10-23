package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.config.PaginationProperties;
import com.cvcompass.backend.ingestion.dto.DocumentMetadata;
import com.cvcompass.backend.ingestion.dto.DocumentDetailRow;
import com.cvcompass.backend.ingestion.dto.DocumentOverview;
import com.cvcompass.backend.ingestion.dto.DocumentProcessing;
import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import com.cvcompass.backend.ingestion.exception.DocumentNotFoundException;
import com.cvcompass.backend.ingestion.repository.DocumentQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Synthetic read projections only; these tests are not evidence of successful OCR execution. */
class DocumentQueryServiceTests {
    private final DocumentQueryRepository repository = mock(DocumentQueryRepository.class);
    private final PaginationProperties properties = new PaginationProperties();
    private final DocumentQueryService service = new DocumentQueryService(repository, properties,
            JsonMapper.builder().build());

    private DocumentOverview overview(long id, String availability, String status) {
        return new DocumentOverview(new DocumentMetadata(id, "synthetic.pdf", 100, "application/pdf",
                "a".repeat(64), DocumentStatus.PENDING, Instant.EPOCH), null, "STORED",
                new DocumentProcessing(null, status, null, null, null, null, null, null, null, availability));
    }

    @Test
    void cursorUsesLookAheadWithoutReturningTheExtraRow() {
        when(repository.list(20L, 3)).thenReturn(List.of(overview(19, "NONE", "PENDING"),
                overview(18, "NONE", "PENDING"), overview(17, "NONE", "PENDING")));
        var page = service.list(20L, 2);
        assertThat(page.content()).extracting(row -> row.metadata().id()).containsExactly(19L, 18L);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextBeforeId()).isEqualTo(18);
    }

    @Test
    void defaultSizeAndEmptyLastPageHaveNoCursor() {
        properties.setDefaultPageSize(3);
        when(repository.list(null, 4)).thenReturn(List.of());
        var page = service.list(null, null);
        assertThat(page.content()).isEmpty();
        assertThat(page.size()).isEqualTo(3);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextBeforeId()).isNull();
        verify(repository).list(null, 4);
    }

    @Test
    void configuredLimitAndInvalidCursorAreRejectedBeforeQuery() {
        properties.setMaxPageSize(2);
        for (int size : List.of(0, -1, 3, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> service.list(null, size)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.list(0L, 1)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void missingDocumentIsNotAnEmptyResult() {
        when(repository.find(1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.find(1)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void noJobPendingAndInfraFailureDoNotInventResults() {
        for (String state : List.of("NOT_SCHEDULED", "PENDING", "PROCESSING", "FAILED")) {
            when(repository.find(1)).thenReturn(Optional.of(new DocumentDetailRow(
                    overview(1, "NONE", state), null, null, null)));
            var detail = service.find(1);
            assertThat(detail.ingestionStatus()).isEqualTo("STORED");
            assertThat(detail.processing().status()).isEqualTo(state);
            assertThat(detail.ocr().availability()).isEqualTo("NONE");
            assertThat(detail.ocr().status()).isNull();
            assertThat(detail.ocr().pages()).isEmpty();
        }
    }

    @Test
    void legacyTextIsPreservedWithoutInventingPageBoundariesOrStatus() {
        when(repository.find(1)).thenReturn(Optional.of(new DocumentDetailRow(
                overview(1, "LEGACY", "FAILED"), 7L, null, "Synthetic legacy\ntext")));
        var result = service.find(1).ocr();
        assertThat(result.availability()).isEqualTo("LEGACY");
        assertThat(result.legacyRawText()).isEqualTo("Synthetic legacy\ntext");
        assertThat(result.pages()).isEmpty();
        assertThat(result.status()).isNull();
    }

    @Test
    void partialPagesPreserveOrderRawTextBlankAndFailure() {
        String json = """
                {"status":"partial","page_count":3,"pages":[
                  {"page_number":1,"status":"ok","method":"native","text":"Synthetic Tiếng Việt\n",
                   "raw_text":" Synthetic Tiếng Việt ","warnings":[],"error":null},
                  {"page_number":2,"status":"blank","method":"none","text":"","raw_text":"",
                   "warnings":["BLANK_PAGE"],"error":null},
                  {"page_number":3,"status":"not_processed","method":"none","text":"","raw_text":"",
                   "warnings":[],"error":{"code":"DOCUMENT_TIMEOUT","stage":"supervisor"}}],
                  "warnings":[],"errors":[{"code":"SUMMARY_PARTIAL","stage":"supervisor"}],
                  "engines":{"private":"must-not-leak"}}
                """.replace("Việt\n", "Việt\\n");
        when(repository.find(1)).thenReturn(Optional.of(new DocumentDetailRow(
                overview(1, "PAGE_RESULT", "PARTIAL"), 8L, json, null)));
        var detail = service.find(1);
        assertThat(detail.ocr().status()).isEqualTo("partial");
        assertThat(detail.ocr().pages()).extracting(page -> page.pageNumber()).containsExactly(1, 2, 3);
        assertThat(detail.ocr().pages().getFirst().text()).isEqualTo("Synthetic Tiếng Việt\n");
        assertThat(detail.ocr().pages().getFirst().rawText()).isEqualTo(" Synthetic Tiếng Việt ");
        assertThat(detail.ocr().pages().get(1).text()).isEmpty();
        assertThat(detail.ocr().pages().get(2).error().code()).isEqualTo("DOCUMENT_TIMEOUT");
        assertThat(JsonMapper.builder().build().writeValueAsString(detail)).doesNotContain("must-not-leak");
    }

    @Test
    void deliveredFailureWithNoPagesIsStillAnAvailableResult() {
        when(repository.find(1)).thenReturn(Optional.of(new DocumentDetailRow(
                overview(1, "PAGE_RESULT", "FAILED"), 9L,
                """
                {"status":"failed","page_count":null,"pages":[],"warnings":[],
                 "errors":[{"code":"FILE_CORRUPT","stage":"preflight"}]}
                """, null)));
        var result = service.find(1).ocr();
        assertThat(result.availability()).isEqualTo("PAGE_RESULT");
        assertThat(result.extractionId()).isEqualTo(9);
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.pageCount()).isNull();
        assertThat(result.pages()).isEmpty();
        assertThat(result.errors().getFirst().code()).isEqualTo("FILE_CORRUPT");
    }
}
