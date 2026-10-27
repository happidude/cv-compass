package com.cvcompass.backend.ingestion.dto;

import java.io.IOException;
import java.io.InputStream;

/** Common streaming input; HTTP adaptation stays in the controller. */
public record ImportFile(String name, long size, Opener opener, String expectedSha256) {
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }
}
