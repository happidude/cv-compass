package com.cvcompass.backend.ingestion.controller;

import com.cvcompass.backend.ingestion.service.OriginalPdfService;
import com.cvcompass.backend.storage.FileStorageService;
import com.cvcompass.backend.storage.StorageException;
import com.cvcompass.backend.storage.StorageKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OriginalPdfController {
    private final OriginalPdfService files;
    private final FileStorageService storage;

    public OriginalPdfController(OriginalPdfService files, FileStorageService storage) {
        this.files = files;
        this.storage = storage;
    }

    @GetMapping("/api/documents/{id}/content")
    public void content(
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean download,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        var metadata = files.metadata(id);
        if (metadata.isEmpty()) {
            error(response, 404, "DOCUMENT_NOT_FOUND");
            return;
        }
        var content = metadata.orElseThrow();
        if (!"application/pdf".equals(content.mime())) {
            error(response, 415, "PDF_REQUIRED");
            return;
        }
        long[] range;
        try {
            range = parseRange(request.getHeader("Range"), content.size());
        } catch (IllegalArgumentException ex) {
            response.setHeader("Content-Range", "bytes */" + content.size());
            error(response, 416, "INVALID_RANGE");
            return;
        }
        InputStream source;
        try {
            source = storage.read(new StorageKey(content.key()));
        } catch (StorageException ex) {
            error(
                    response,
                    ex.reason() == StorageException.Reason.NOT_FOUND ? 404 : 503,
                    ex.reason() == StorageException.Reason.NOT_FOUND
                            ? "STORAGE_FILE_MISSING"
                            : "STORAGE_UNAVAILABLE");
            return;
        }
        try (source) {
            source.skipNBytes(range[0]);
            response.setContentType(MediaType.APPLICATION_PDF_VALUE);
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(
                    "Content-Disposition",
                    (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                            .filename("document-" + id + ".pdf")
                            .build()
                            .toString());
            long remaining = range[1] - range[0] + 1;
            response.setContentLengthLong(remaining);
            if (request.getHeader("Range") != null) {
                response.setStatus(206);
                response.setHeader(
                        "Content-Range",
                        "bytes " + range[0] + "-" + range[1] + "/" + content.size());
            }
            byte[] buffer = new byte[65536];
            while (remaining > 0) {
                int count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException();
                response.getOutputStream().write(buffer, 0, count);
                remaining -= count;
            }
        } catch (IOException ex) {
            if (!response.isCommitted()) {
                response.reset();
                error(response, 503, "STORAGE_UNAVAILABLE");
            }
        }
    }

    static long[] parseRange(String value, long size) {
        if (size < 0) throw new IllegalArgumentException();
        if (value == null) return new long[] {0, size - 1};
        if (!Pattern.matches("bytes=[0-9]*-[0-9]*", value) || size == 0)
            throw new IllegalArgumentException();
        try {
            String[] parts = value.substring(6).split("-", -1);
            long start, end;
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix < 1) throw new IllegalArgumentException();
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
            }
            if (start < 0 || start >= size || end < start) throw new IllegalArgumentException();
            return new long[] {start, end};
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException();
        }
    }

    private static void error(HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "private, no-store");
        response.getWriter()
                .write(
                        "{\"code\":\""
                                + code
                                + "\",\"message\":\"Request could not be completed\",\"correlationId\":null,\"details\":[]}");
    }
}
