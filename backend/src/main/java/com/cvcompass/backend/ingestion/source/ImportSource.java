package com.cvcompass.backend.ingestion.source;

import com.cvcompass.backend.ingestion.dto.SourceFile;
import java.io.*;
import java.util.function.Consumer;

public interface ImportSource extends AutoCloseable {
    String key();
    void scan(Consumer<SourceFile> visitor) throws IOException;
    SourceFile inspect(String name) throws IOException;
    String fingerprint(SourceFile file) throws IOException;
    InputStream open(SourceFile file) throws IOException;
    void move(SourceFile file, String sha256, boolean successful, String target) throws IOException;
    void close() throws IOException;
}
