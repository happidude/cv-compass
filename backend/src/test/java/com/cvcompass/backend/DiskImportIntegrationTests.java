package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.service.*;
import com.cvcompass.backend.ingestion.source.*;
import com.cvcompass.backend.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "cv.import.enabled=true","cv.import.batch-size=2","cv.import.scan-interval=1d",
    "cv.import.stability-interval=1s","cv.import.retry-backoff=1s","cv.import.max-attempts=3"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DiskImportIntegrationTests {
    static final IsolatedPostgresSchema DATABASE=new IsolatedPostgresSchema();
    static final Path INBOX=inbox();
    static final String PDF="%PDF-1.7\nsynthetic\n%%EOF\n";
    @Autowired DiskImportService importer;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ImportSource source;
    @MockitoSpyBean FileStorageService storage;
    @MockitoBean(name="diskImportClock") Clock clock;
    final AtomicReference<Instant> now=new AtomicReference<>();
    static Path inbox() { try { return Files.createTempDirectory("cv-import-test-"); } catch(IOException e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) throws Exception {
        DATABASE.configure(registry);
        registry.add("cv.import.source-directory",()->INBOX.toString());
    }
    @BeforeEach void prepare() throws Exception {
        now.set(Instant.now().plusSeconds(3600)); when(clock.instant()).thenAnswer(call -> now.get());
        for(String table:List.of("CV_DISK_IMPORT_FILES","CV_DISK_OBSERVATIONS","CV_IMPORT_ITEMS","CV_DOCUMENTS","CV_IMPORT_BATCHES")) jdbc.execute("DELETE FROM \""+table+"\"");
        for(Path root:List.of(INBOX.resolve("processed"),INBOX.resolve("failed"),DATABASE.storageRoot())) {
            try(var files=Files.list(root)) { for(var file:files.toList()) Files.delete(file); }
        }
        try(var files=Files.list(INBOX)) { for(var file:files.toList()) if(Files.isRegularFile(file)&&!file.getFileName().toString().startsWith(".")) Files.delete(file); }
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(DATABASE.schemaName());
    }
    @AfterAll static void cleanup() throws Exception {
        new TestContextManager(DiskImportIntegrationTests.class).getTestContext().markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        try(var paths=Files.walk(INBOX)) { for(var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        try(var paths=Files.list(DATABASE.storageRoot())) { for(var path:paths.toList()) Files.delete(path); }
        DATABASE.close();
    }
    void advance() { now.updateAndGet(time -> time.plusSeconds(2)); }
    void stableTurn() { importer.tick(); advance(); importer.tick(); }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM \""+table+"\"",Long.class); }
    String state() { return jdbc.queryForObject("SELECT \"STATE\" FROM \"CV_DISK_IMPORT_FILES\"",String.class); }
    void put(String name,String content) throws Exception { Files.writeString(INBOX.resolve(name),content); }

    @Test void drainsStableOrderedBatchesAndPreservesFailedResults() throws Exception {
        put("c.pdf",PDF); put("a.pdf",PDF); put("b.pdf","invalid");
        importer.tick(); assertThat(count("CV_IMPORT_BATCHES")).isZero();
        advance(); importer.tick();
        assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(2);
        assertThat(count("CV_DOCUMENTS")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT \"DISPLAY_NAME\" FROM \"CV_IMPORT_ITEMS\" ORDER BY \"ID\"",String.class)).containsExactly("a.pdf","b.pdf","c.pdf");
        assertThat(jdbc.queryForList("SELECT \"STATUS\" FROM \"CV_IMPORT_BATCHES\" ORDER BY \"ID\"",String.class)).containsExactly("PARTIAL_FAILED","COMPLETED");
        assertThat(jdbc.queryForObject("SELECT sum(\"ATTEMPTS\") FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_DISK_IMPORT_FILES\" WHERE \"STATE\"='FAILED'",Integer.class)).isEqualTo(1);
        assertThat(count("CV_PROCESSING_JOBS")).isZero(); assertThat(count("CANDIDATES")).isZero();
        importer.tick(); assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(2);
    }
    @Test void changingFilesWaitWithoutAttemptsAndTemporaryFilesAreIgnored() throws Exception {
        put("a.pdf",PDF); put("x.tmp.pdf",PDF); importer.tick(); advance();
        put("a.pdf",PDF+"changed"); importer.tick();
        assertThat(count("CV_DISK_IMPORT_FILES")).isZero(); assertThat(count("CV_IMPORT_BATCHES")).isZero();
        put("a.pdf",PDF); importer.tick(); advance(); importer.tick();
        assertThat(count("CV_DOCUMENTS")).isEqualTo(1);
    }
    @Test void moveFailureThenRetryNeverUploadsSuccessAgain() throws Exception {
        put("a.pdf",PDF);
        doThrow(new IOException("synthetic move failure")).when(source).move(any(),anyString(),anyBoolean(),anyString());
        stableTurn(); assertThat(state()).isEqualTo("SUCCEEDED_MOVE"); assertThat(INBOX.resolve("a.pdf")).exists();
        doCallRealMethod().when(source).move(any(),anyString(),anyBoolean(),anyString());
        advance(); importer.tick();
        assertThat(state()).isEqualTo("PROCESSED"); assertThat(count("CV_DOCUMENTS")).isEqualTo(1);
        assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(1); assertThat(INBOX.resolve("a.pdf")).doesNotExist();
    }
    @Test void transientStorageFailureRetriesThreeTotalAttemptsThenFails() throws Exception {
        put("a.pdf",PDF);
        doThrow(new StorageException(StorageException.Reason.WRITE_FAILED,"synthetic")).when(storage).store(any());
        stableTurn(); assertThat(state()).isEqualTo("RETRY_WAIT");
        importer.tick(); assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(1);
        advance(); importer.tick(); advance(); importer.tick();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(3);
        assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(3); assertThat(count("CV_DOCUMENTS")).isZero();
        importer.tick(); assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(3);
    }
    @Test void unavailableStorageStopsTurnAndUnresolvedAttemptRequiresAttention() throws Exception {
        put("a.pdf",PDF); put("b.pdf",PDF);
        doThrow(new StorageException(StorageException.Reason.UNAVAILABLE,"synthetic")).when(storage).store(any());
        stableTurn(); assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(1); assertThat(count("CV_IMPORT_ITEMS")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_DISK_IMPORT_FILES\" WHERE \"STATE\"='IMPORTING'",Integer.class)).isEqualTo(1);
        doCallRealMethod().when(storage).store(any()); advance(); importer.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"CV_DISK_IMPORT_FILES\" WHERE \"STATE\"='NEEDS_ATTENTION'",Integer.class)).isEqualTo(1);
        advance(); importer.tick();
        assertThat(count("CV_DOCUMENTS")).isEqualTo(1);
    }
    @Test void durableSuccessSurvivesNewRunnerAndSameNameNewContentIsImported() throws Exception {
        put("a.pdf",PDF); stableTurn();
        put("a.pdf",PDF); stableTurn(); assertThat(count("CV_DOCUMENTS")).isEqualTo(1);
        put("a.pdf",PDF.replace("synthetic","new synthetic")); stableTurn();
        assertThat(count("CV_DOCUMENTS")).isEqualTo(2); assertThat(count("CV_DISK_IMPORT_FILES")).isEqualTo(2);
    }
    @Test void historyCommitFailureRollsBackDocumentItemCounterAndCompensates() throws Exception {
        put("a.pdf",PDF);
        jdbc.execute("CREATE FUNCTION reject_import_outcome() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.\"STATE\" = ''SUCCEEDED_MOVE'' THEN RAISE EXCEPTION ''synthetic history commit failure''; END IF; RETURN NEW; END'");
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_import_outcome AFTER UPDATE ON \"CV_DISK_IMPORT_FILES\" DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_import_outcome()");
        try {
            stableTurn(); assertThat(count("CV_DOCUMENTS")).isZero(); assertThat(count("CV_IMPORT_ITEMS")).isZero();
            assertThat(jdbc.queryForObject("SELECT \"SUCCESS_COUNT\" FROM \"CV_IMPORT_BATCHES\"",Integer.class)).isZero();
            assertThat(state()).isEqualTo("IMPORTING"); assertThat(INBOX.resolve("a.pdf")).exists();
            try(var files=Files.list(DATABASE.storageRoot())) { assertThat(files.count()).isZero(); }
        } finally { jdbc.execute("DROP TRIGGER reject_import_outcome ON \"CV_DISK_IMPORT_FILES\""); jdbc.execute("DROP FUNCTION reject_import_outcome()"); }
    }
    @Test void overlappingTicksDoNotCreateOverlappingWork() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); }).when(source).scan(any());
        try(var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(importer::tick); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try { threads.submit(importer::tick).get(5,TimeUnit.SECONDS); }
            finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
        }
        verify(source,times(1)).scan(any());
    }
    @Test void moveRetriesAreBoundedSeparatelyAndSourceIsKept() throws Exception {
        put("a.pdf",PDF);
        doThrow(new IOException("synthetic move failure")).when(source).move(any(),anyString(),anyBoolean(),anyString());
        stableTurn(); advance(); importer.tick(); advance(); importer.tick();
        now.updateAndGet(time -> time.plusSeconds(10)); importer.tick();
        assertThat(state()).isEqualTo("NEEDS_ATTENTION"); assertThat(INBOX.resolve("a.pdf")).exists();
        assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT \"MOVE_ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(3);
        assertThat(count("CV_DOCUMENTS")).isEqualTo(1);
    }
    @Test void missingCommittedStorageRequiresAttentionInsteadOfReupload() throws Exception {
        put("a.pdf",PDF);
        doThrow(new IOException("synthetic move failure")).when(source).move(any(),anyString(),anyBoolean(),anyString());
        stableTurn();
        try(var files=Files.list(DATABASE.storageRoot())) { for(var file:files.toList()) Files.delete(file); }
        advance(); importer.tick();
        assertThat(state()).isEqualTo("NEEDS_ATTENTION"); assertThat(INBOX.resolve("a.pdf")).exists();
        assertThat(jdbc.queryForObject("SELECT \"MOVE_ERROR_CODE\" FROM \"CV_DISK_IMPORT_FILES\"",String.class)).isEqualTo("STORED_FILE_MISSING");
        assertThat(count("CV_DOCUMENTS")).isEqualTo(1);
    }
    @Test void sourceChangingAfterFingerprintDoesNotConsumeAttempt() throws Exception {
        put("a.pdf",PDF);
        doAnswer(call -> { throw new com.cvcompass.backend.ingestion.exception.SourceChangedException(); }).when(source).open(any());
        // fingerprint uses the real open method too, so only inject the change after fingerprint completes.
        doAnswer(call -> { com.cvcompass.backend.ingestion.dto.SourceFile file=call.getArgument(0);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(PDF.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }).when(source).fingerprint(any());
        stableTurn();
        assertThat(state()).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isZero();
        assertThat(count("CV_DOCUMENTS")).isZero();
        assertThat(jdbc.queryForObject("SELECT \"ERROR_CODE\" FROM \"CV_IMPORT_ITEMS\"",String.class)).isEqualTo("SOURCE_CHANGED");
    }
    @Test void batchCreationCommitFailureHasBoundedDurableClaims() throws Exception {
        put("a.pdf",PDF);
        jdbc.execute("CREATE FUNCTION reject_disk_batch() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic batch creation failure''; END'");
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_disk_batch AFTER INSERT ON \"CV_IMPORT_BATCHES\" DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_disk_batch()");
        try {
            stableTurn(); assertThat(state()).isEqualTo("QUEUED");
            for(int turn=0;turn<10;turn++) { advance(); importer.tick(); }
            assertThat(state()).isEqualTo("NEEDS_ATTENTION");
            assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(3);
            assertThat(count("CV_IMPORT_BATCHES")).isZero(); assertThat(count("CV_DOCUMENTS")).isZero();
            assertThat(INBOX.resolve("a.pdf")).exists();
        } finally { jdbc.execute("DROP TRIGGER reject_disk_batch ON \"CV_IMPORT_BATCHES\""); jdbc.execute("DROP FUNCTION reject_disk_batch()"); }
    }
    @Test void preparationReadFailuresAndImportRetriesShareOneAttemptBudget() throws Exception {
        put("a.pdf",PDF);
        doThrow(new IOException("synthetic hash read failure")).doCallRealMethod().when(source).fingerprint(any());
        doThrow(new StorageException(StorageException.Reason.WRITE_FAILED,"synthetic")).when(storage).store(any());
        stableTurn(); advance(); importer.tick();
        assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(2);
        advance(); importer.tick();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(3);
        assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(2);
    }
    @Test void readFailuresAfterAnImportFailureStillStopAtThreeTotalAttempts() throws Exception {
        put("a.pdf",PDF);
        doThrow(new StorageException(StorageException.Reason.WRITE_FAILED,"synthetic")).when(storage).store(any());
        stableTurn();
        doThrow(new IOException("synthetic read failure")).when(source).fingerprint(any());
        advance(); importer.tick(); advance(); importer.tick(); advance(); importer.tick();
        assertThat(state()).isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT \"READ_ATTEMPTS\" FROM \"CV_DISK_OBSERVATIONS\"",Integer.class)).isEqualTo(2);
        verify(source,times(3)).fingerprint(any()); assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(1);
    }
    @Test void fingerprintAndStoredContentMismatchCompensatesWithoutConsumingAttempt() throws Exception {
        put("a.pdf",PDF); doReturn("a".repeat(64)).when(source).fingerprint(any());
        stableTurn(); assertThat(state()).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("SELECT \"ATTEMPTS\" FROM \"CV_DISK_IMPORT_FILES\"",Integer.class)).isZero();
        assertThat(count("CV_DOCUMENTS")).isZero();
        assertThat(jdbc.queryForObject("SELECT \"ERROR_CODE\" FROM \"CV_IMPORT_ITEMS\"",String.class)).isEqualTo("SOURCE_CHANGED");
        try(var files=Files.list(DATABASE.storageRoot())) { assertThat(files.count()).isZero(); }
    }
    @Test void actualStorageRootLossStopsBeforeCreatingAnyBatch() throws Exception {
        put("a.pdf",PDF); put("b.pdf",PDF); importer.tick(); advance();
        Path root=DATABASE.storageRoot(); Files.delete(root); Files.writeString(root,"unavailable directory");
        try {
            importer.tick();
            assertThat(count("CV_IMPORT_BATCHES")).isZero(); assertThat(count("CV_IMPORT_ITEMS")).isZero();
            assertThat(INBOX.resolve("a.pdf")).exists(); assertThat(INBOX.resolve("b.pdf")).exists();
        } finally {
            Files.delete(root); Files.createDirectory(root);
            if(Files.getFileStore(root).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }
    @Test void writeFailureFollowedByFailedProbeAbortsInsteadOfFailingEveryFile() throws Exception {
        put("a.pdf",PDF); put("b.pdf",PDF); importer.tick(); advance();
        doThrow(new StorageException(StorageException.Reason.WRITE_FAILED,"synthetic")).when(storage).store(any());
        doNothing().doThrow(new StorageException(StorageException.Reason.UNAVAILABLE,"synthetic")).when(storage).verifyAvailable();
        importer.tick();
        assertThat(count("CV_IMPORT_BATCHES")).isEqualTo(1); assertThat(count("CV_IMPORT_ITEMS")).isZero();
        assertThat(jdbc.queryForObject("SELECT \"FAILED_COUNT\" FROM \"CV_IMPORT_BATCHES\"",Integer.class)).isZero();
        assertThat(INBOX.resolve("a.pdf")).exists(); assertThat(INBOX.resolve("b.pdf")).exists();
        verify(storage,times(1)).store(any());
    }
    @Test void unreadableFingerprintBackoffDoesNotStarveOtherFiles() throws Exception {
        put("a.pdf",PDF); put("b.pdf",PDF);
        doAnswer(call -> { com.cvcompass.backend.ingestion.dto.SourceFile file=call.getArgument(0); if(file.name().equals("a.pdf")) throw new IOException("synthetic read failure"); return call.callRealMethod(); }).when(source).fingerprint(any());
        stableTurn(); assertThat(count("CV_DOCUMENTS")).isEqualTo(1);
        advance(); importer.tick(); advance(); importer.tick();
        assertThat(jdbc.queryForObject("SELECT \"READ_ATTEMPTS\" FROM \"CV_DISK_OBSERVATIONS\" WHERE \"FILE_NAME\"='a.pdf'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT \"ERROR_CODE\" FROM \"CV_DISK_OBSERVATIONS\" WHERE \"FILE_NAME\"='a.pdf'",String.class)).isEqualTo("SOURCE_READ_NEEDS_ATTENTION");
    }
}
