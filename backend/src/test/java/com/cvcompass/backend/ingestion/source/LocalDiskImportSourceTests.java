package com.cvcompass.backend.ingestion.source;

import static org.assertj.core.api.Assertions.*;

import com.cvcompass.backend.ingestion.exception.SourceChangedException;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LocalDiskImportSourceTests {
    @TempDir Path directory;

    private LocalDiskImportSource source() throws Exception {
        Path inbox = directory.resolve("inbox");
        Files.createDirectories(inbox);
        return new LocalDiskImportSource(
                inbox.toString(), null, null, directory.resolve("objects").toString());
    }

    @Test
    void scansOnlyDirectPublishedRegularPdfFiles() throws Exception {
        try (var source = source()) {
            Path root = directory.resolve("inbox");
            for (String name :
                    List.of(
                            "b.pdf",
                            "a.PDF",
                            ".hidden.pdf",
                            "a.tmp.pdf",
                            "a.part.pdf",
                            "a.copying.pdf",
                            "copy.tmp")) Files.writeString(root.resolve(name), "fixture");
            Files.createDirectory(root.resolve("folder.pdf"));
            Files.writeString(root.resolve("processed/old.pdf"), "fixture");
            Files.writeString(root.resolve("failed/old.pdf"), "fixture");
            var found = new ArrayList<String>();
            source.scan(file -> found.add(file.name()));
            assertThat(found).containsExactlyInAnyOrder("a.PDF", "b.pdf");
        }
    }

    @Test
    void lockExcludesSecondRunnerAndReleasesAfterClose() throws Exception {
        try (var first = source()) {
            assertThatThrownBy(this::source).isInstanceOf(IOException.class);
        }
        try (var second = source()) {
            assertThat(second.key()).hasSize(64);
        }
    }

    @Test
    void streamingFingerprintAndSnapshotGuard() throws Exception {
        try (var source = source()) {
            Path file = directory.resolve("inbox/a.pdf");
            Files.writeString(file, "synthetic");
            var snapshot = source.inspect("a.pdf");
            String hash = source.fingerprint(snapshot);
            assertThat(hash).hasSize(64);
            try (var input = source.open(snapshot)) {
                assertThat(new String(input.readAllBytes())).isEqualTo("synthetic");
            }
            Files.writeString(file, "changed content");
            assertThatThrownBy(() -> source.open(snapshot))
                    .isInstanceOf(SourceChangedException.class);
        }
    }

    @Test
    void detectsChangesDuringStreamWithoutMovingSource() throws Exception {
        try (var source = source()) {
            Path file = directory.resolve("inbox/a.pdf");
            Files.writeString(file, "synthetic");
            var snapshot = source.inspect("a.pdf");
            try (var input = source.open(snapshot)) {
                input.readAllBytes();
                Files.writeString(file, "changed content");
                assertThatThrownBy(input::read).isInstanceOf(SourceChangedException.class);
            }
            assertThat(file).exists();
        }
    }

    @Test
    void moveDoesNotOverwriteAndCanResumeAnUnlinkBeforeDatabaseUpdate() throws Exception {
        try (var source = source()) {
            Path file = directory.resolve("inbox/a.pdf");
            Files.writeString(file, "synthetic");
            var snapshot = source.inspect("a.pdf");
            String hash = source.fingerprint(snapshot);
            String name = "a".repeat(32) + ".pdf";
            Path target = directory.resolve("inbox/processed").resolve(name);
            Files.writeString(target, "other");
            assertThatThrownBy(() -> source.move(snapshot, hash, true, name))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(Files.readString(target)).isEqualTo("other");
            assertThat(file).exists();
            Files.delete(target);
            Files.createLink(target, file);
            source.move(snapshot, hash, true, name);
            source.move(snapshot, hash, true, name);
            assertThat(file).doesNotExist();
            assertThat(Files.readString(target)).isEqualTo("synthetic");
        }
    }

    @Test
    void failureMovePreservesContentAndChangedSnapshotIsRejected() throws Exception {
        try (var source = source()) {
            Path file = directory.resolve("inbox/a.pdf");
            Files.writeString(file, "synthetic");
            var snapshot = source.inspect("a.pdf");
            String hash = source.fingerprint(snapshot);
            String name = "b".repeat(32) + ".pdf";
            Files.writeString(file, "different");
            assertThatThrownBy(() -> source.move(snapshot, hash, false, name))
                    .isInstanceOf(SourceChangedException.class);
            var updated = source.inspect("a.pdf");
            String updatedHash = source.fingerprint(updated);
            source.move(updated, updatedHash, false, name);
            assertThat(Files.readString(directory.resolve("inbox/failed").resolve(name)))
                    .isEqualTo("different");
        }
    }

    @Test
    void rejectsSymbolicFilesOrWindowsReparsePointsWithoutTouchingTarget() throws Exception {
        try (var source = source()) {
            Path outside = Files.createDirectory(directory.resolve("outside"));
            Path sentinel = Files.writeString(outside.resolve("sentinel.pdf"), "outside");
            Path link = directory.resolve("inbox/linked.pdf");
            if (System.getProperty("os.name").startsWith("Windows")) {
                var process =
                        new ProcessBuilder(
                                        "cmd",
                                        "/c",
                                        "mklink",
                                        "/J",
                                        link.toString(),
                                        outside.toString())
                                .redirectErrorStream(true)
                                .start();
                String output =
                        new String(
                                process.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.waitFor()).as(output).isZero();
            } else Files.createSymbolicLink(link, sentinel);
            try {
                var found = new ArrayList<String>();
                source.scan(file -> found.add(file.name()));
                assertThat(found).isEmpty();
                assertThatThrownBy(() -> source.inspect("linked.pdf"))
                        .isInstanceOf(SourceChangedException.class);
                assertThat(Files.readString(sentinel)).isEqualTo("outside");
            } finally {
                Files.delete(link);
            }
        }
    }

    @Test
    void linkedDestinationCannotCauseSourceUnlinkEvenWhenItResolvesToSameFile() throws Exception {
        try (var source = source()) {
            Path from = directory.resolve("inbox/a.pdf");
            Files.writeString(from, "synthetic");
            var snapshot = source.inspect("a.pdf");
            String hash = source.fingerprint(snapshot);
            String name = "c".repeat(32) + ".pdf";
            Path to = directory.resolve("inbox/processed").resolve(name);
            Files.createLink(to, from);
            Path actualDestination = to.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path actualSource = from.toRealPath(LinkOption.NOFOLLOW_LINKS);
            var unsafe =
                    org.mockito.Mockito.mock(java.nio.file.attribute.BasicFileAttributes.class);
            org.mockito.Mockito.when(unsafe.isRegularFile()).thenReturn(true);
            org.mockito.Mockito.when(unsafe.isSymbolicLink()).thenReturn(true);
            try (var files =
                    org.mockito.Mockito.mockStatic(
                            Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                files.when(
                                () ->
                                        Files.readAttributes(
                                                actualDestination,
                                                java.nio.file.attribute.BasicFileAttributes.class,
                                                LinkOption.NOFOLLOW_LINKS))
                        .thenReturn(unsafe);
                assertThatThrownBy(() -> source.move(snapshot, hash, true, name))
                        .isInstanceOf(FileAlreadyExistsException.class);
                assertThat(Files.readString(from)).isEqualTo("synthetic");
                files.verify(() -> Files.delete(actualSource), org.mockito.Mockito.never());
            }
            assertThat(Files.readString(to)).isEqualTo("synthetic");
        }
    }

    @Test
    void rejectsUnsafeLayoutAndTraversal() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        assertThatThrownBy(
                        () ->
                                new LocalDiskImportSource(
                                        root.toString(),
                                        null,
                                        null,
                                        root.resolve("objects").toString()))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(
                        () ->
                                new LocalDiskImportSource(
                                        root.toString(),
                                        directory.resolve("outside").toString(),
                                        null,
                                        directory.resolve("objects").toString()))
                .isInstanceOf(IOException.class);
        try (var source = source()) {
            assertThatThrownBy(() -> source.inspect("../outside.pdf"))
                    .isInstanceOf(IOException.class);
        }
    }
}
