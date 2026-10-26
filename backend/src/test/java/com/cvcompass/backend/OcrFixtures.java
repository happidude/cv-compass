package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.dto.OcrClaim;

/** Synthetic strings only; no PDFs, storage access or candidate fields. */
final class OcrFixtures {
    private OcrFixtures() {}

    static String success(OcrClaim claim) {
        return envelope(
                "a_" + claim.attemptId().toString().replace("-", ""),
                Long.toString(claim.documentId()),
                claim.profileRevision());
    }

    static String envelope(String request, String document, String profile) {
        return """
            {"protocol":"cv-compass.ocr-job/1.0","profile_revision":"%s","exit_code":0,"delivered":true,
             "provenance":{"worker_commit":"%s","image_id":"sha256:%s"},
             "ocr_result":{"type":"result","schema_version":"cv-compass.ocr/1.0","request_id":"%s",
             "document_id":"%s","status":"success","page_count":1,
             "pages":[{"page_number":1,"status":"ok","method":"native","text":"Synthetic text",
             "raw_text":"Synthetic text","warnings":[],"error":null,
             "timings_ms":{"native":1,"render":null,"preprocess":null,"ocr":null,"total":1},"confidence":null}],
             "warnings":[],"errors":[],"timings_ms":{"preflight":1,"cleanup":1,"total":3},
             "engines":{"python":null,"pdfplumber":null,"pdfminer_six":null,"pypdfium2":null,"pdfium":null,
             "tesseract":null,"leptonica":null,"models":[],"pillow":null,"worker_version":null},"config_fingerprint":null}}
            """
                .formatted(profile, "a".repeat(40), "b".repeat(64), request, document);
    }
}
