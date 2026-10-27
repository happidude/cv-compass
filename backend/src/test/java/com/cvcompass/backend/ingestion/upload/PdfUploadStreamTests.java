package com.cvcompass.backend.ingestion.upload;

import static org.assertj.core.api.Assertions.*;

import com.cvcompass.backend.ingestion.exception.UploadRejectedException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class PdfUploadStreamTests {
    @Test
    void emptyFileIsRejected() {
        assertRejected(
                () -> PdfUploadStream.open(InputStream.nullInputStream()),
                UploadRejectedException.Reason.EMPTY_FILE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not a PDF",
                "%PDF-1.8\n%%EOF",
                "%PDF-9.0\n%%EOF",
                "%PDF-1.7\nmissing-end",
                "%PDF-1.7\n%%EOF\ntrailing-script",
                "%PDF-2.0\n%%EOF\ntrailing-data"
            })
    void invalidSignaturesAndTrailersAreRejected(String content) {
        assertRejected(
                () ->
                        PdfUploadStream.open(bytes(content))
                                .transferTo(OutputStream.nullOutputStream()),
                UploadRejectedException.Reason.INVALID_PDF);
    }

    @Test
    void supportsPdfTwoAndSingleByteReads() throws Exception {
        byte[] content = "%PDF-2.0\nsynthetic\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        var stream = PdfUploadStream.open(new ByteArrayInputStream(content));
        for (byte value : content) assertThat(stream.read()).isEqualTo(value & 0xff);
        assertThat(stream.read()).isEqualTo(-1);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void acceptsExactLimitUsingStreamingContent() throws Exception {
        var input = PdfUploadStream.open(pdfOfSize(PdfUploadStream.MAX_BYTES));
        assertThat(input.transferTo(OutputStream.nullOutputStream()))
                .isEqualTo(PdfUploadStream.MAX_BYTES);
    }

    @Test
    void rejectsOneByteAboveLimitEvenWithoutClientSize() {
        assertRejected(
                () ->
                        PdfUploadStream.open(pdfOfSize(PdfUploadStream.MAX_BYTES + 1))
                                .transferTo(OutputStream.nullOutputStream()),
                UploadRejectedException.Reason.FILE_TOO_LARGE);
    }

    @Test
    void skipCannotBypassValidationAndResetIsDisabled() throws Exception {
        var stream = PdfUploadStream.open(bytes("%PDF-1.7\n%%EOF\n"));
        assertThat(stream.skip(100)).isEqualTo(15);
        assertThat(stream.markSupported()).isFalse();
        assertThatThrownBy(stream::reset).isInstanceOf(IOException.class);
        assertRejected(
                () -> PdfUploadStream.open(bytes("%PDF-1.7\ninvalid")).skip(100),
                UploadRejectedException.Reason.INVALID_PDF);
    }

    @Test
    void wrapperDoesNotCloseCallerStream() throws Exception {
        var input =
                new ByteArrayInputStream("%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII)) {
                    boolean closed;

                    @Override
                    public void close() {
                        closed = true;
                    }
                };
        PdfUploadStream.open(input).close();
        assertThat(input.closed).isFalse();
    }

    public static InputStream pdfOfSize(long size) {
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] trailer = "\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        return new InputStream() {
            long position;

            @Override
            public int read() {
                if (position == size) return -1;
                long index = position++;
                if (index < header.length) return header[(int) index];
                if (index >= size - trailer.length)
                    return trailer[(int) (index - size + trailer.length)];
                return ' ';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
                if (length == 0) return 0;
                if (position == size) return -1;
                int count = (int) Math.min(length, size - position);
                for (int index = 0; index < count; index++) buffer[offset + index] = (byte) read();
                return count;
            }
        };
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII));
    }

    private static void assertRejected(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            UploadRejectedException.Reason reason) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        UploadRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
