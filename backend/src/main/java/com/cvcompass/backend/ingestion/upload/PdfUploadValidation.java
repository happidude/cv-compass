package com.cvcompass.backend.ingestion.upload;

import java.io.IOException;
import java.io.InputStream;

/** Opens a bounded, borrowed PDF stream without exposing the stream implementation. */
public final class PdfUploadValidation {
    public static final long MAX_BYTES = PdfUploadStream.MAX_BYTES;

    private PdfUploadValidation() { }

    public static InputStream open(InputStream source, long maxBytes) throws IOException {
        return PdfUploadStream.open(source, maxBytes);
    }
}
