package com.cvcompass.backend.ingestion.api;

import java.util.List;
import java.util.Optional;

/** Read-only, fixed extraction identity. No latest-result lookup or ingestion writes. */
public interface OcrExtractionReader {
    Optional<Extraction> find(long extractionId);

    List<Long> scanAfter(long afterId, long upperId, int limit);

    long upperId();

    record Extraction(
            long id,
            long documentId,
            String schemaVersion,
            String status,
            boolean legacy,
            List<Page> pages,
            String profileRevision,
            List<String> warnings) {
        public Extraction {
            pages = List.copyOf(pages);
            warnings = List.copyOf(warnings);
        }

        public Extraction(
                long id,
                long documentId,
                String schemaVersion,
                String status,
                boolean legacy,
                List<Page> pages) {
            this(id, documentId, schemaVersion, status, legacy, pages, null, List.of());
        }
    }

    record Page(int number, String status, String rawText, List<String> warnings) {
        public Page {
            warnings = List.copyOf(warnings);
        }

        public Page(int number, String status, String rawText) {
            this(number, status, rawText, List.of());
        }
    }
}
