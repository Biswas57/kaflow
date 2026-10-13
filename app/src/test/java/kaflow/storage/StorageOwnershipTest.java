package kaflow.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static kaflow.storage.StorageException.Code.*;
import static kaflow.storage.StorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class StorageOwnershipTest {
    @TempDir Path directory;

    @Test
    void canonicalAliasesAndAnotherJvmCannotOwnTheDirectory() throws Exception {
        Path data = directory.resolve("data");
        Path alias = directory.resolve("alias");
        try (PartitionLog first = open(data)) {
            Files.createSymbolicLink(alias, data);
            fails(OWNERSHIP, () -> open(alias));
            fails(OWNERSHIP, () -> open(data.resolve("..").resolve("data")));
            // Also proves rejecting a same-JVM contender did not release the original OS lock.
            assertEquals("CONFLICT", probe(data));
            first.append(Optional.empty(), EMPTY);
        }
        assertEquals("OWNED", probe(alias));
        assertTrue(Files.exists(data.resolve(".kaflow.lock"))); // Stable lock inode survives clean close.
    }

    @Test
    void nonregularLockEntryIsRejectedWithoutOpeningIt() throws Exception {
        Path data = directory.resolve("data");
        Files.createDirectory(data);
        Path target = directory.resolve("unrelated");
        Files.writeString(target, "unchanged");
        Files.createSymbolicLink(data.resolve(".kaflow.lock"), target);
        fails(CORRUPT, () -> open(data));
        assertEquals("unchanged", Files.readString(target));
        Files.delete(data.resolve(".kaflow.lock"));
        try (PartitionLog log = open(data)) { assertEquals(0, log.captureBoundary().endOffset()); }
    }

    @Test
    void wrongThreadCannotReadWriteOrCloseEvenWhenOwnerHasAdmittedNoWork() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (PartitionLog log = open(directory)) {
            var boundary = log.captureBoundary();
            var task = executor.submit(() -> {
                fails(WRONG_THREAD, () -> log.append(Optional.empty(), EMPTY));
                fails(WRONG_THREAD, () -> log.read(ID, 0, 1, 0, boundary));
                fails(WRONG_THREAD, log::captureBoundary);
                fails(WRONG_THREAD, log::close);
            });
            task.get(5, TimeUnit.SECONDS);
            fails(OWNERSHIP, () -> open(directory));
            assertEquals(0, log.append(Optional.empty(), EMPTY).offset());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void pausedWriterRetainsOwnershipAndCannotBeClosedByAnotherThread() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var reference = new java.util.concurrent.atomic.AtomicReference<PartitionLog>();
        StorageIo real = StorageIo.system();
        StorageIo paused = (path, mode) -> {
            var file = real.open(path, mode);
            return new StorageIo.File() {
                @Override public int read(java.nio.ByteBuffer data, long position) throws java.io.IOException { return file.read(data, position); }
                @Override public long size() throws java.io.IOException { return file.size(); }
                @Override public void close() throws java.io.IOException { file.close(); }
                @Override public int write(java.nio.ByteBuffer data, long position) throws java.io.IOException {
                    if (path.toString().endsWith(".log") && position >= 38) {
                        writing.countDown();
                        try { assertTrue(proceed.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                    }
                    return file.write(data, position);
                }
            };
        };
        try {
            var task = executor.submit(() -> {
                try (PartitionLog log = PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, paused)) {
                    reference.set(log);
                    return log.append(Optional.empty(), EMPTY);
                }
            });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            fails(WRONG_THREAD, () -> reference.get().close());
            fails(OWNERSHIP, () -> open(directory));
            assertEquals("CONFLICT", probe(directory));
            proceed.countDown();
            assertEquals(0, task.get(5, TimeUnit.SECONDS).offset());
            assertEquals("OWNED", probe(directory));
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private String probe(Path path) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of(DirectoryLockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator
                + Path.of(PartitionLog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path output = Files.createTempFile("kaflow-lock-probe-", ".log");
        Process process = new ProcessBuilder(javaExecutable, "-cp", classpath, DirectoryLockProbe.class.getName(), path.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "lock probe timed out");
            String text = Files.readString(output).strip();
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
            Files.deleteIfExists(output);
        }
    }
}
