package com.cvcompass.backend.ingestion.upload;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;

import static com.cvcompass.backend.ingestion.upload.UploadRejectedException.Reason.*;

/**
 * Bounded, borrowed stream with signature/trailer validation, not a PDF parser or malware scanner.
 * Throws validation failures while storage is still staging, before publication.
 */
final class PdfUploadStream extends FilterInputStream {
    static final long MAX_BYTES = 10L * 1024 * 1024;
    private final byte[] tail = new byte[1024];
    private long count;
    private boolean validated;

    static PdfUploadStream open(InputStream source) throws IOException {
        var input = new PushbackInputStream(source, 8);
        byte[] header = input.readNBytes(8);
        if (header.length == 0) {
            throw new UploadRejectedException(EMPTY_FILE, "File must not be empty");
        }
        String signature = new String(header, StandardCharsets.ISO_8859_1);
        if (!signature.matches("%PDF-(1\\.[0-7]|2\\.0)")) {
            throw new UploadRejectedException(INVALID_PDF, "File must have a supported PDF signature");
        }
        input.unread(header);
        return new PdfUploadStream(input);
    }

    private PdfUploadStream(InputStream input) {
        super(input);
    }

    @Override
    public int read() throws IOException {
        int value = in.read();
        if (value == -1) {
            validateEnd();
        } else {
            checkSize(1);
            tail[(int) (count++ % tail.length)] = (byte) value;
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        int requested = (int) Math.min(length, MAX_BYTES - count + 1);
        int read = in.read(bytes, offset, requested);
        if (read == -1) {
            validateEnd();
        } else {
            checkSize(read);
            for (int index = 0; index < read; index++) {
                tail[(int) (count++ % tail.length)] = bytes[offset + index];
            }
        }
        return read;
    }

    private void checkSize(int read) {
        if (read > MAX_BYTES - count) {
            throw new UploadRejectedException(FILE_TOO_LARGE, "File exceeds the 10 MiB limit");
        }
    }

    private void validateEnd() {
        if (validated) return;
        int length = (int) Math.min(count, tail.length);
        byte[] ending = new byte[length];
        long first = count - length;
        for (int index = 0; index < length; index++) {
            ending[index] = tail[(int) ((first + index) % tail.length)];
        }
        String trailer = new String(ending, StandardCharsets.ISO_8859_1);
        if (!trailer.matches("(?s).*%%EOF[\\x00\\x09\\x0A\\x0C\\x0D\\x20]*")) {
            throw new UploadRejectedException(INVALID_PDF, "PDF end marker is missing or invalid");
        }
        validated = true;
    }

    @Override
    public long skip(long amount) throws IOException {
        // Do not allow consumers to bypass byte accounting or trailer checks.
        long remaining = Math.max(0, amount);
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (read < 0) break;
            remaining -= read;
        }
        return amount < 0 ? 0 : amount - remaining;
    }

    @Override public boolean markSupported() { return false; }
    @Override public void mark(int readLimit) { }
    @Override public void reset() throws IOException { throw new IOException("Reset is not supported"); }
    @Override public void close() { /* Caller retains ownership of the original stream. */ }
}
