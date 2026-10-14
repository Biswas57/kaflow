package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** OS ownership, plus a process guard against opening/closing another channel on a locked inode. */
final class DirectoryLease implements AutoCloseable {
    private static final byte[] DURABLE_MARKER = "KAFLOW-DURABLE-1\n".getBytes(StandardCharsets.US_ASCII);
    // This tracks only live OS lock resources, never broker/log state. Some POSIX systems
    // release process locks when *any* channel on the inode closes, hence the guard.
    private static final Set<Path> CLAIMED = ConcurrentHashMap.newKeySet();
    final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    private DirectoryLease(Path directory, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.channel = channel;
        this.lock = lock;
    }

    static DirectoryLease acquire(Path requested) throws IOException {
        Files.createDirectories(requested);
        Path directory = requested.toRealPath();
        if (!CLAIMED.add(directory)) throw conflict();
        FileChannel channel = null;
        try {
            Path lockPath = directory.resolve(".kaflow.lock");
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException(StorageException.Code.CORRUPT, "nonregular lock file");
            }
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw conflict();
            }
            if (lock == null) throw conflict();
            return new DirectoryLease(directory, channel, lock);
        } catch (IOException | RuntimeException | Error failure) {
            if (channel != null) {
                try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            CLAIMED.remove(directory);
            throw failure;
        }
    }

    private static StorageException conflict() {
        return new StorageException(StorageException.Code.OWNERSHIP, "data directory already owned");
    }

    /** Uses the owning lock channel: closing a second channel could release POSIX locks. */
    boolean hasDurableMarker() throws IOException {
        if (channel.size() == 0) return false;
        if (channel.size() != DURABLE_MARKER.length) throw LogFormat.corrupt("invalid durable root marker");
        ByteBuffer marker = ByteBuffer.allocate(DURABLE_MARKER.length);
        transferMarker(marker, false);
        if (!java.util.Arrays.equals(marker.array(), DURABLE_MARKER)) throw LogFormat.corrupt("invalid durable root marker");
        return true;
    }

    void markDurable() throws IOException {
        if (channel.size() != 0) throw LogFormat.corrupt("root marker already exists");
        transferMarker(ByteBuffer.wrap(DURABLE_MARKER), true);
        channel.force(true);
    }

    private void transferMarker(ByteBuffer data, boolean write) throws IOException {
        int zeroes = 0;
        while (data.hasRemaining()) {
            int count = write ? channel.write(data, data.position()) : channel.read(data, data.position());
            if (count < 0) throw LogFormat.corrupt("incomplete root marker");
            if (count == 0 && ++zeroes == 8) throw new StorageException(StorageException.Code.IO_STALLED, "root marker made no progress");
            if (count > 0) zeroes = 0;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try { lock.release(); } catch (IOException e) { failure = e; }
        try { channel.close(); } catch (IOException e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        } finally {
            CLAIMED.remove(directory);
        }
        if (failure != null) throw failure;
    }
}
