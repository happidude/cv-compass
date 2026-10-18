package com.cvcompass.backend.storage.local;

import com.cvcompass.backend.storage.StorageException;
import com.cvcompass.backend.storage.StorageKey;
import com.cvcompass.backend.storage.StoredFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.cvcompass.backend.storage.StorageException.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalFileStorageServiceTests {
    @TempDir Path directory;

    @Test
    void binaryRoundTripChecksumSizeExistenceAndIdempotentDelete() throws Exception {
        var storage = storage();
        byte[] bytes = {0, 1, -1, 13, 10, 0, 127};
        var file = storage.store(new ByteArrayInputStream(bytes));
        assertThat(file.sizeBytes()).isEqualTo(bytes.length);
        assertThat(file.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(file.key().value()).matches("[a-f0-9]{32}");
        assertThat(storage.exists(file.key())).isTrue();
        try (var input = storage.read(file.key())) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
        storage.delete(file.key());
        storage.delete(file.key());
        assertThat(storage.exists(file.key())).isFalse();
        assertReason(() -> storage.read(file.key()), NOT_FOUND);
        assertNoTemporaryFiles();
    }

    @Test
    void emptyFilesAreValid() throws Exception {
        var storage = storage();
        var file = storage.store(InputStream.nullInputStream());
        assertThat(file.sizeBytes()).isZero();
        assertThat(file.sha256()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        try (var input = storage.read(file.key())) {
            assertThat(input.read()).isEqualTo(-1);
        }
    }

    @Test
    void missingObjectHasDefinedSemantics() {
        var storage = storage();
        var missing = new StorageKey("0".repeat(32));
        assertThat(storage.exists(missing)).isFalse();
        storage.delete(missing);
        assertReason(() -> storage.read(missing), NOT_FOUND);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../secret", "..", "/etc/passwd", "C:/secrets", "C:\secrets",
            "\\server\share", "a/b", "a\b", "%2e%2e%2f", ".write-test.tmp",
            " abc", "AABB", "AABBCCDDEEFF00112233445566778899", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsMalformedAndPathLikeKeys(String key) {
        assertReason(() -> new StorageKey(key), INVALID_KEY);
    }

    @Test
    void nullKeysFailConsistently() {
        var storage = storage();
        assertReason(() -> storage.read(null), INVALID_KEY);
        assertReason(() -> storage.exists(null), INVALID_KEY);
        assertReason(() -> storage.delete(null), INVALID_KEY);
    }

    @Test
    void streamsLargeContentInBoundedChunksAndDoesNotCloseCallerStream() throws Exception {
        long size = 32L * 1024 * 1024 + 17;
        var expectedDigest = MessageDigest.getInstance("SHA-256");
        var source = new InputStream() {
            long remaining = size;
            boolean closed;
            @Override public int read() { throw new AssertionError("Use bulk streaming"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                assertThat(length).isLessThanOrEqualTo(64 * 1024);
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                java.util.Arrays.fill(bytes, offset, offset + count, (byte) 37);
                expectedDigest.update(bytes, offset, count);
                remaining -= count;
                return count;
            }
            @Override public void close() { closed = true; }
        };
        var storage = storage();
        var result = storage.store(source);
        assertThat(source.closed).isFalse();
        assertThat(result.sizeBytes()).isEqualTo(size);
        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(expectedDigest.digest()));
        try (var input = storage.read(result.key())) {
            assertThat(input.transferTo(java.io.OutputStream.nullOutputStream())).isEqualTo(size);
        }
    }

    @Test
    void failedInputLeavesNoPublishedOrTemporaryFileAndRetainsCause() throws Exception {
        var storage = storage();
        var failure = new IOException("synthetic input failure");
        var source = new InputStream() {
            boolean first = true;
            boolean closed;
            @Override public int read() throws IOException { throw failure; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (first) { first = false; bytes[offset] = 12; return 1; }
                throw failure;
            }
            @Override public void close() { closed = true; }
        };
        assertThatThrownBy(() -> storage.store(source)).isInstanceOf(StorageException.class)
                .hasCause(failure).hasMessage("Could not store file");
        assertThat(source.closed).isFalse();
        try (var files = Files.list(root())) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void collisionNeverOverwritesExistingObjectAndCleansPartialWrite() throws Exception {
        var key = new StorageKey("1".repeat(32));
        var storage = new LocalFileStorageService(root().toString(), () -> key);
        storage.store(new ByteArrayInputStream(new byte[]{1}));
        assertReason(() -> storage.store(new ByteArrayInputStream(new byte[]{2})), WRITE_FAILED);
        try (var input = storage.read(key)) {
            assertThat(input.readAllBytes()).containsExactly((byte) 1);
        }
        assertNoTemporaryFiles();
    }

    @Test
    void concurrentStoresHaveDistinctKeysAndCompleteContents() throws Exception {
        var storage = storage();
        List<java.util.concurrent.Future<StoredFile>> writes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 32; index++) {
                int value = index;
                writes.add(executor.submit(() -> storage.store(new ByteArrayInputStream(new byte[]{(byte) value}))));
            }
            var keys = new java.util.HashSet<StorageKey>();
            for (int index = 0; index < writes.size(); index++) {
                var file = writes.get(index).get(20, TimeUnit.SECONDS);
                assertThat(keys.add(file.key())).isTrue();
                try (var input = storage.read(file.key())) {
                    assertThat(input.readAllBytes()).containsExactly((byte) index);
                }
            }
        }
        assertNoTemporaryFiles();
    }

    @Test
    void incompleteWritesCannotBeReadOrListedAsObjects() throws Exception {
        var key = new StorageKey("2".repeat(32));
        var storage = new LocalFileStorageService(root().toString(), () -> key);
        var input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                assertThat(storage.exists(key)).isFalse();
                assertReason(() -> storage.read(key), NOT_FOUND);
                return super.read(bytes, offset, length);
            }
        };
        storage.store(input);
        assertThat(storage.exists(key)).isTrue();
    }

    @Test
    void callerClosesReadResource() throws Exception {
        var storage = storage();
        var stored = storage.store(new ByteArrayInputStream(new byte[]{1}));
        var input = storage.read(stored.key());
        input.close();
        assertThatThrownBy(input::read).isInstanceOf(IOException.class);
        storage.delete(stored.key()); // Also verifies the handle was released on Windows.
    }

    @Test
    void missingNestedDirectoriesAreCreated() {
        var nested = directory.resolve("missing/deep/objects");
        new LocalFileStorageService(nested.toString());
        assertThat(nested).isDirectory();
    }

    @Test
    void unsafeRootsFailWithoutExposingAbsolutePaths() throws Exception {
        Files.createDirectory(directory.resolve(".git"));
        assertReason(() -> new LocalFileStorageService(directory.resolve("objects").toString()), UNAVAILABLE);
        for (String invalid : List.of("relative/storage", directory.getRoot().toString(),
                directory.resolve("a/../b").toString())) {
            assertThatThrownBy(() -> new LocalFileStorageService(invalid)).isInstanceOf(StorageException.class)
                    .hasMessageNotContaining(directory.toString());
        }
        Path file = Files.write(directory.resolve("file"), new byte[]{1});
        assertReason(() -> new LocalFileStorageService(file.toString()), UNAVAILABLE);
    }

    @Test
    void rejectsLinkedRootAncestorsAndLinkedObjects() throws Exception {
        var storage = storage();
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.write(outside.resolve("sentinel"), new byte[]{42});
        Path linkedObject = root().resolve("3".repeat(32));
        createDirectoryLink(linkedObject, outside);
        try {
            var key = new StorageKey(linkedObject.getFileName().toString());
            assertReason(() -> storage.read(key), READ_FAILED);
            assertReason(() -> storage.exists(key), READ_FAILED);
            assertReason(() -> storage.delete(key), DELETE_FAILED);
            assertReason(() -> new LocalFileStorageService(linkedObject.resolve("child").toString()), UNAVAILABLE);
            assertThat(Files.readAllBytes(outside.resolve("sentinel"))).containsExactly((byte) 42);
        } finally {
            Files.delete(linkedObject); // Remove the link itself, never recursively traverse it.
        }
    }

    @Test
    void refusesUnavailableRootAfterStartup() throws Exception {
        var storage = storage();
        Files.delete(root());
        Files.write(root(), new byte[]{1});
        assertReason(() -> storage.exists(new StorageKey("4".repeat(32))), READ_FAILED);
        assertReason(() -> storage.store(InputStream.nullInputStream()), WRITE_FAILED);
    }

    @Test
    void unwritableDirectoryIsRejectedWithoutDependingOnAdministratorPrivileges() {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.isWritable(root())).thenReturn(false);
            assertReason(this::storage, UNAVAILABLE);
        }
    }

    @Test
    void unsupportedAtomicPublicationFailsAndCleansUpInsteadOfCopying() throws Exception {
        var storage = storage();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(any(Path.class), any(Path.class)))
                    .thenThrow(new UnsupportedOperationException("synthetic no hard links"));
            assertReason(() -> storage.store(new ByteArrayInputStream(new byte[]{1})), WRITE_FAILED);
        }
        try (var files = Files.list(root())) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void posixRootsRequirePrivatePermissionsOrWindowsRootIsUsable() throws Exception {
        var storage = storage();
        if (Files.getFileStore(root()).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root(), PosixFilePermissions.fromString("rwxrwxrwx"));
            try {
                assertReason(() -> storage.exists(new StorageKey("5".repeat(32))), READ_FAILED);
            } finally {
                Files.setPosixFilePermissions(root(), PosixFilePermissions.fromString("rwx------"));
            }
        } else {
            assertThat(storage.exists(new StorageKey("5".repeat(32)))).isFalse();
        }
    }

    @Test
    void uncheckedInputFailureAlsoCleansPartialFilesAndKeepsCause() throws Exception {
        var storage = storage();
        var failure = new IllegalStateException("synthetic unchecked input failure");
        var input = new InputStream() {
            @Override public int read() { throw failure; }
        };
        assertThatThrownBy(() -> storage.store(input)).isInstanceOf(StorageException.class).hasCause(failure);
        try (var files = Files.list(root())) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void startupPublicationFailureKeepsOriginalCauseWhenCleanupAlsoFails() {
        var publicationFailure = new IOException("synthetic publication failure");
        var cleanupFailure = new IOException("synthetic cleanup failure");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(any(Path.class), any(Path.class))).thenThrow(publicationFailure);
            files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(cleanupFailure);
            assertThatThrownBy(this::storage).isInstanceOf(StorageException.class)
                    .hasCause(publicationFailure);
            assertThat(publicationFailure.getSuppressed()).contains(cleanupFailure);
        }
    }
    @Test
    void diskWriteSetupFailureIsReportedWithoutPublishing() throws Exception {
        var storage = storage();
        var diskFull = new IOException("synthetic disk full");
        // A CALLS_REAL_METHODS when(...) stub would itself create an unrelated temp file.
        // Supply the failure as the default answer so arranging the test has no I/O side effect.
        try (var files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("createTempFile")) {
                throw diskFull;
            }
            return invocation.callRealMethod();
        })) {
            assertThatThrownBy(() -> storage.store(InputStream.nullInputStream()))
                    .isInstanceOf(StorageException.class).hasCause(diskFull);
        }
        try (var files = Files.list(root())) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void failedStagingUnlinkRollsBackPublicationAndCleansUp() throws Exception {
        var storage = storage();
        var unlinkFailure = new IOException("synthetic unlink failure");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.delete(any(Path.class))).thenThrow(unlinkFailure);
            assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(new byte[]{1})))
                    .isInstanceOf(StorageException.class).hasCause(unlinkFailure);
        }
        try (var files = Files.list(root())) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void objectAccessFailuresAreNotMistakenForAbsence() throws Exception {
        var storage = storage();
        var key = new StorageKey("6".repeat(32));
        var target = root().resolve(key.value());
        var denied = new java.nio.file.AccessDeniedException("synthetic object access denied");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenThrow(denied);
            assertReason(() -> storage.exists(key), READ_FAILED);
            assertReason(() -> storage.read(key), READ_FAILED);
            assertReason(() -> storage.delete(key), DELETE_FAILED);
        }
    }

    @Test
    void concurrentKeyCollisionsPublishExactlyOneObject() throws Exception {
        var key = new StorageKey("7".repeat(32));
        var storage = new LocalFileStorageService(root().toString(), () -> key);
        var barrier = new java.util.concurrent.CyclicBarrier(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    try {
                        storage.store(new ByteArrayInputStream(new byte[]{7}));
                        return true;
                    } catch (StorageException exception) {
                        assertThat(exception.reason()).isEqualTo(WRITE_FAILED);
                        return false;
                    }
                }));
            }
            int successes = 0;
            for (var result : results) {
                if (result.get(20, TimeUnit.SECONDS)) successes++;
            }
            assertThat(successes).isEqualTo(1);
        }
        try (var input = storage.read(key)) {
            assertThat(input.readAllBytes()).containsExactly((byte) 7);
        }
        assertNoTemporaryFiles();
    }
    @Test
    void availabilityProbePublishesNothingAndDetectsWriteSetupFailure() throws Exception {
        var storage=storage(); storage.verifyAvailable(1);
        try(var files=Files.list(root())) { assertThat(files.count()).isZero(); }
        var failure=new IOException("synthetic full disk");
        try(var files=mockStatic(Files.class, invocation -> {
            if(invocation.getMethod().getName().equals("createTempFile")) throw failure;
            return invocation.callRealMethod();
        })) {
            assertThatThrownBy(storage::verifyAvailable).isInstanceOf(StorageException.class).hasCause(failure);
            assertReason(storage::verifyAvailable,UNAVAILABLE);
        }
    }
    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // NTFS junctions need no symlink privilege; exercise Windows reparse points reliably.
            var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private LocalFileStorageService storage() { return new LocalFileStorageService(root().toString()); }
    private Path root() { return directory.resolve("objects"); }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(root())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith("."));
        }
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
                                     StorageException.Reason reason) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(StorageException.class,
                exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
