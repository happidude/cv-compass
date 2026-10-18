package com.cvcompass.backend.ingestion.source;

import com.cvcompass.backend.ingestion.dto.SourceFile;
import com.cvcompass.backend.ingestion.exception.SourceChangedException;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Local filesystem only; OS lock excludes a second runner using the same inbox. */
public final class LocalDiskImportSource implements ImportSource {
    private final Path root;
    private final Path processed;
    private final Path failed;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public LocalDiskImportSource(String directory, String processedDirectory, String failedDirectory, String storage) throws IOException {
        try {
            root = safeDirectory(Path.of(directory), false).toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path objects = canonical(Path.of(storage).toAbsolutePath().normalize());
            if (root.startsWith(objects) || objects.startsWith(root)) throw new IOException("Import/storage overlap");
            processed = destination(processedDirectory, "processed");
            failed = destination(failedDirectory, "failed");
            if (processed.equals(failed)) throw new IOException("Import destinations overlap");
            lockChannel = FileChannel.open(root.resolve(".cv-compass-import.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock acquired;
            try { acquired = lockChannel.tryLock(); }
            catch (IOException | RuntimeException failure) { lockChannel.close(); throw failure; }
            if (acquired == null) { lockChannel.close(); throw new IOException("Import source already owned"); }
            lock = acquired;
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Automatic import requires a private local inbox, separate storage and exclusive ownership", failure);
        }
    }

    private Path destination(String configured, String fallback) throws IOException {
        Path path = configured == null || configured.isBlank() ? root.resolve(fallback) : canonical(Path.of(configured));
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !root.equals(path.getParent())) {
            throw new IOException("Import destinations must be direct children of inbox");
        }
        safeDirectory(path, true);
        if (!Files.getFileStore(root).equals(Files.getFileStore(path))) throw new IOException("Import requires one filesystem");
        // Verify no-overwrite hard-link support at startup, not a copy/delete fallback.
        Path probe = Files.createTempFile(root, ".import-probe-", ".tmp");
        Path link = path.resolve(".import-probe-" + UUID.randomUUID());
        Throwable primary=null;
        try { Files.createLink(link, probe); }
        catch(IOException | RuntimeException failure) { primary=failure; throw failure; }
        finally {
            IOException cleanupFailure=null;
            for(Path temporary:List.of(link,probe)) {
                try { Files.deleteIfExists(temporary); }
                catch(IOException cleanup) {
                    if(primary!=null) primary.addSuppressed(cleanup);
                    else if(cleanupFailure==null) cleanupFailure=cleanup;
                    else cleanupFailure.addSuppressed(cleanup);
                }
            }
            if(primary==null && cleanupFailure!=null) throw cleanupFailure;
        }
        return path;
    }

    private static Path canonical(Path path) throws IOException {
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IOException("Unsafe directory");
        Path ancestor=path;
        var missing=new ArrayDeque<Path>();
        while (!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)) {
            missing.push(ancestor.getFileName()); ancestor=ancestor.getParent();
        }
        Path resolved=ancestor.toRealPath(LinkOption.NOFOLLOW_LINKS);
        while (!missing.isEmpty()) resolved=resolved.resolve(missing.pop());
        return resolved;
    }

    private static Path safeDirectory(Path path, boolean create) throws IOException {
        if (!path.isAbsolute() || path.getParent() == null || !path.equals(path.normalize())) throw new IOException("Unsafe import directory");
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (create && current.equals(path) && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                    || Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) throw new IOException("Unsafe import ancestor");
        }
        if (!Files.isReadable(path) || !Files.isWritable(path)) throw new IOException("Import directory unavailable");
        return path;
    }

    @Override public String key() { return hash(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    private void owned() throws IOException {
        if (!lock.isValid()) throw new IOException("Import source ownership lost");
        safeDirectory(root, false);
    }

    @Override public void scan(Consumer<SourceFile> visitor) throws IOException {
        owned();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || !lower.endsWith(".pdf") || lower.endsWith(".tmp.pdf")
                        || lower.endsWith(".part.pdf") || lower.endsWith(".copying.pdf")) continue;
                try { visitor.accept(inspect(name)); }
                catch (NoSuchFileException | SourceChangedException ignored) { /* Producer is changing the inventory. */ }
            }
        }
    }

    private Path path(String name) throws IOException {
        Path leaf = Path.of(name);
        if (leaf.isAbsolute() || leaf.getNameCount() != 1 || name.equals(".") || name.equals("..")) throw new IOException("Unsafe source name");
        Path resolved=root.resolve(leaf).normalize();
        if (!resolved.isAbsolute() || !root.equals(resolved.getParent())) throw new IOException("Unsafe source path");
        return resolved;
    }

    @Override public SourceFile inspect(String name) throws IOException {
        owned();
        var attrs = Files.readAttributes(path(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new SourceChangedException();
        return new SourceFile(name, attrs.size(), attrs.lastModifiedTime().toInstant().toString(), String.valueOf(attrs.fileKey()));
    }

    private void unchanged(SourceFile expected) throws IOException {
        try { if (!inspect(expected.name()).equals(expected)) throw new SourceChangedException(); }
        catch (NoSuchFileException missing) { throw new SourceChangedException(); }
    }

    @Override public InputStream open(SourceFile file) throws IOException {
        unchanged(file);
        var input=Files.newInputStream(path(file.name()), LinkOption.NOFOLLOW_LINKS);
        return new InputStream() {
            @Override public void close() throws IOException { input.close(); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                unchanged(file);
                int result = input.read(bytes, offset, length);
                if (result == -1) unchanged(file);
                return result;
            }
            @Override public int read() throws IOException {
                unchanged(file);
                int result = input.read();
                if (result == -1) unchanged(file);
                return result;
            }
        };
    }

    @Override public String fingerprint(SourceFile file) throws IOException {
        try (var input = open(file)) { return digest(input); }
    }

    @Override public void move(SourceFile file, String sha256, boolean successful, String target) throws IOException {
        owned();
        if (!target.matches("[a-f0-9]{32}\\.pdf")) throw new IOException("Unsafe move target");
        Path destination = successful ? processed : failed;
        safeDirectory(destination, false);
        Path to = destination.resolve(target);
        Path from = path(file.name());
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(to)) {
                try (var input = Files.newInputStream(to, LinkOption.NOFOLLOW_LINKS)) {
                    if (digest(input).equals(sha256)) return; // Unlinked before previous DB update.
                }
            }
            throw new SourceChangedException();
        }
        unchanged(file);
        if (!fingerprint(file).equals(sha256)) throw new SourceChangedException();
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            var attrs=Files.readAttributes(to,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new FileAlreadyExistsException("Unsafe import destination");
            if (!Files.isSameFile(from, to)) throw new FileAlreadyExistsException("Import destination occupied");
        } else Files.createLink(to, from);
        unchanged(file);
        // Keep a durable second link if unlink fails; next move can reconcile it without overwriting.
        Files.delete(from);
    }

    private static String digest(InputStream input) throws IOException {
        var sha = sha256();
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) sha.update(buffer, 0, count);
        return HexFormat.of().formatHex(sha.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hash(byte[] value) { return HexFormat.of().formatHex(sha256().digest(value)); }
    @Override public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }
}
