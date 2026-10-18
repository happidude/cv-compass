package com.cvcompass.backend.storage.local;

import com.cvcompass.backend.storage.FileStorageService;
import com.cvcompass.backend.storage.StorageException;
import com.cvcompass.backend.storage.StorageKey;
import com.cvcompass.backend.storage.StoredFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.cvcompass.backend.storage.StorageException.Reason.*;

/**
 * Local, flat-key adapter. Root and ancestors must be controlled by the service/operator,
 * with no untrusted local writers. NOFOLLOW checks reject existing links/reparse points;
 * they are not a substitute for OS permissions against hostile concurrent directory swaps.
 */
public class LocalFileStorageService implements FileStorageService {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final int BUFFER_SIZE = 64 * 1024;
    private final Path root;
    private final Supplier<StorageKey> keys;

    public LocalFileStorageService(String rootDirectory) {
        this(rootDirectory, () -> new StorageKey(UUID.randomUUID().toString().replace("-", "")));
    }

    // Deterministic collision testing without exposing caller-selected write keys.
    LocalFileStorageService(String rootDirectory, Supplier<StorageKey> keys) {
        this.keys = Objects.requireNonNull(keys);
        try {
            if (rootDirectory == null || rootDirectory.isBlank()) {
                throw new IOException("A local root directory is required");
            }
            Path configured = Path.of(rootDirectory);
            if (!configured.isAbsolute() || configured.getParent() == null
                    || !configured.equals(configured.normalize())) {
                throw new IOException("An absolute, normalized, non-filesystem-root directory is required");
            }
            root = configured;
            verifyAncestors(true);
            if (Files.getFileStore(existingAncestor(root)).supportsFileAttributeView("posix")) {
                Files.createDirectories(root, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(root);
            }
            verifyRoot();
            verifyPublicationSupport();
        } catch (IOException | InvalidPathException | UnsupportedOperationException | SecurityException exception) {
            throw new StorageException(UNAVAILABLE,
                    "Local storage unavailable: configure cv.storage.local.root-directory as a private, writable "
                            + "absolute directory outside a Git checkout on a filesystem supporting hard links", exception);
        }
    }

    @Override
    public void verifyAvailable() {
        try {
            verifyRoot();
            verifyPublicationSupport();
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            throw new StorageException(UNAVAILABLE, "File storage is unavailable", failure);
        }
    }
    @Override
    public void verifyAvailable(long requiredBytes) {
        verifyAvailable();
        try {
            if (requiredBytes < 0 || Files.getFileStore(root).getUsableSpace() < requiredBytes) {
                throw new IOException("Insufficient storage capacity");
            }
        } catch (IOException | SecurityException failure) {
            throw new StorageException(UNAVAILABLE, "File storage is unavailable", failure);
        }
    }
    @Override
    public StoredFile store(InputStream content) {
        Objects.requireNonNull(content, "content");
        Path temporary = null;
        Path published = null;
        try {
            verifyRoot();
            StorageKey key = Objects.requireNonNull(keys.get());
            Path target = resolve(key);
            temporary = Files.createTempFile(root, ".write-", ".tmp");
            MessageDigest digest = sha256();
            long size = 0;
            // Only the output channel is owned here; the caller retains its input stream.
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = content.read(buffer)) != -1) {
                    if (count == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, count);
                    size = Math.addExact(size, count);
                    var bytes = ByteBuffer.wrap(buffer, 0, count);
                    while (bytes.hasRemaining()) {
                        output.write(bytes);
                    }
                }
                output.force(true);
            }
            verifyRoot();
            // Atomic, create-if-absent publication. Unlike ATOMIC_MOVE, this cannot replace
            // an existing destination. Unsupported hard links fail; there is no copy fallback.
            Files.createLink(target, temporary);
            published = target;
            Files.delete(temporary);
            return new StoredFile(key, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException exception) {
            cleanup(published, exception);
            cleanup(temporary, exception);
            throw new StorageException(WRITE_FAILED, "Could not store file", exception);
        }
    }

    @Override
    public InputStream read(StorageKey key) {
        Path target = resolve(key);
        try {
            verifyRoot();
            if (!regularObject(target)) {
                throw new StorageException(NOT_FOUND, "Stored file was not found");
            }
            return Channels.newInputStream(Files.newByteChannel(target, Set.of(StandardOpenOption.READ, NOFOLLOW)));
        } catch (NoSuchFileException exception) {
            throw new StorageException(NOT_FOUND, "Stored file was not found", exception);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw new StorageException(READ_FAILED, "Could not read stored file", exception);
        }
    }

    @Override
    public void delete(StorageKey key) {
        Path target = resolve(key);
        try {
            verifyRoot();
            if (regularObject(target)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw new StorageException(DELETE_FAILED, "Could not delete stored file", exception);
        }
    }

    @Override
    public boolean exists(StorageKey key) {
        Path target = resolve(key);
        try {
            verifyRoot();
            return regularObject(target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            throw new StorageException(READ_FAILED, "Could not check stored file", exception);
        }
    }

    private Path resolve(StorageKey key) {
        if (key == null) {
            throw new StorageException(INVALID_KEY, "Invalid storage key");
        }
        // StorageKey's closed grammar permits a single portable segment only.
        return root.resolve(key.value());
    }

    private boolean regularObject(Path target) throws IOException {
        try {
            var attributes = Files.readAttributes(target, BasicFileAttributes.class, NOFOLLOW);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Stored object is not a regular file");
            }
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private void verifyRoot() throws IOException {
        verifyAncestors(false);
        if (!Files.isWritable(root) || !Files.isReadable(root)) {
            throw new IOException("Storage directory must be readable and writable");
        }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(root, NOFOLLOW);
            if (permissions.stream().anyMatch(permission ->
                    permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))
                    || !permissions.containsAll(Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))) {
                throw new IOException("Storage root must have owner-only rwx permissions");
            }
        }
    }

    private void verifyAncestors(boolean allowMissing) throws IOException {
        Path current = root.getRoot();
        for (Path segment : root) {
            current = current.resolve(segment);
            try {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, NOFOLLOW);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                        || !current.toRealPath().equals(current.toRealPath(NOFOLLOW))) {
                    throw new IOException("Storage ancestors must be real directories without links");
                }
                // Covers repositories and linked worktrees (.git directory or file).
                if (Files.exists(current.resolve(".git"), NOFOLLOW)) {
                    throw new IOException("Storage must be outside a Git checkout");
                }
            } catch (NoSuchFileException exception) {
                if (!allowMissing) {
                    throw exception;
                }
            }
        }
    }

    private static Path existingAncestor(Path path) {
        Path current = path;
        while (!Files.exists(current, NOFOLLOW)) {
            current = current.getParent();
        }
        return current;
    }

    private void verifyPublicationSupport() throws IOException {
        Path probe = Files.createTempFile(root, ".probe-", ".tmp");
        Path link = root.resolve(".probe-link-" + UUID.randomUUID());
        try {
            try (var output = FileChannel.open(probe, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(new byte[] {0});
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            Files.createLink(link, probe);
            try {
                Files.createLink(link, probe);
                throw new IOException("Filesystem did not enforce create-if-absent publication");
            } catch (FileAlreadyExistsException expected) {
                // Required no-overwrite guarantee.
            }
            Files.delete(link);
            Files.delete(probe);
        } catch (IOException | RuntimeException exception) {
            cleanup(link, exception);
            cleanup(probe, exception);
            throw exception;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not support SHA-256", exception);
        }
    }

    private static void cleanup(Path path, Exception original) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException | RuntimeException failure) {
                if (failure != original) {
                    original.addSuppressed(failure);
                }
            }
        }
    }
}
