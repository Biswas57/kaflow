package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

/** Real persistence primitives and a deterministic, package-private failure seam. */
class Persistence {
    final StorageIo io;
    Persistence() { this(StorageIo.system()); }
    Persistence(StorageIo io) { this.io = io; }

    void event(String point) throws IOException { }

    void checkEnvironment(Path directory) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String type = Files.getFileStore(directory).type().toLowerCase(Locale.ROOT);
        if ((!os.contains("linux") && !os.contains("mac"))
                || !Set.of("ext4", "ext3", "xfs", "btrfs", "apfs", "hfs", "overlay").contains(type)) {
            throw new IOException("unsupported durable filesystem environment");
        }
    }

    void syncDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    void replace(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    void atomic(Path target, ByteBuffer bytes, String kind, int zeroLimit) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (StorageIo.File file = io.open(temporary, StorageIo.Mode.CREATE)) {
            StorageIo.writeFully(file, bytes, 0, zeroLimit);
            event(kind + "-written");
            file.force();
            event(kind + "-forced");
        }
        replace(temporary, target);
        event(kind + "-replaced");
        syncDirectory(target.getParent());
        event(kind + "-synced");
    }

    void truncate(Path path, long length) throws IOException {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            file.truncate(length);
            file.force(true);
        }
        event("recovery-truncated");
    }

    void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
        event("recovery-deleted");
    }
}
