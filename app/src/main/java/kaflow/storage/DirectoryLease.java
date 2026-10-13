package kaflow.storage;

import java.io.IOException;
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
