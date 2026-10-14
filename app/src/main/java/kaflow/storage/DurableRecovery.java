package kaflow.storage;

import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static kaflow.storage.StorageException.Code.*;

/** Read-only planning precedes all repairs. Checkpoints are never changed by recovery. */
final class DurableRecovery {
    record Plan(Path directory, Path lastLog, long lastBytes, List<Path> remove) { }

    static List<Path> entries(Path directory, int max) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path p : stream) {
                if (paths.size() == max) throw new StorageException(LIMIT, "directory entry bound");
                if (Files.isSymbolicLink(p)) throw LogFormat.corrupt("symlink storage entry");
                paths.add(p);
            }
        }
        paths.sort(Comparator.comparing(p -> p.getFileName().toString())); return paths;
    }

    static Plan validate(Path directory, TopicPartition id, DurableMetadata.Checkpoint checkpoint,
                         Persistence disk, StorageOptions options) throws IOException {
        List<Path> logs = new ArrayList<>(), remove = new ArrayList<>();
        for (Path path : entries(directory, 2 * options.maxSegments() + 2)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("nonregular partition entry");
            String name = path.getFileName().toString();
            if (name.equals(".kaflow.lock")) continue;
            if (name.equals(".index-rebuild.tmp")) { remove.add(path); continue; }
            if (!name.matches("[0-9]{20}\\.(log|idx)")) throw LogFormat.corrupt("unexpected partition entry");
            long base = base(path);
            if (name.endsWith(".log")) {
                if (logs.size() == options.maxSegments()) throw new StorageException(LIMIT, "segment bound");
                logs.add(path);
            } else if (base > checkpoint.base()) remove.add(path);
        }
        byte[] topic = LogFormat.topicBytes(id);
        long next = 0; Path last = null;
        for (Path path : logs) {
            long base = base(path);
            try (StorageIo.File file = disk.io.open(path, StorageIo.Mode.READ)) {
                long size = file.size();
                if (size > options.segmentBytes()) throw new StorageException(LIMIT, "segment too large");
                if (base > checkpoint.base()) {
                    if (base < checkpoint.end()) throw LogFormat.corrupt("segment overlaps published range");
                    DurableMetadata.checkVersion(path, LogFormat.SEGMENT_MAGIC, disk, options);
                    scanTailVersions(file, 32 + topic.length, size, options);
                    remove.add(path); continue;
                }
                if (base != next) throw LogFormat.corrupt("published segment gap");
                long end = base == checkpoint.base() ? checkpoint.bytes() : size;
                if (end > size) throw LogFormat.corrupt("truncated published segment");
                LogFormat.Header header = LogFormat.readHeader(file, id, topic, base, end, options);
                long position = header.length();
                while (position < end) {
                    LogFormat.Frame frame = LogFormat.readFrame(file, id, position, end, next, options);
                    next++; position += frame.length();
                }
                if (base == checkpoint.base()) {
                    last = path;
                    scanTailVersions(file, end, size, options);
                } else if (next == base) throw LogFormat.corrupt("empty published nonfinal segment");
            }
        }
        if (last == null || next != checkpoint.end()) throw LogFormat.corrupt("checkpoint has missing/inconsistent published records");
        return new Plan(directory, last, checkpoint.bytes(), List.copyOf(remove));
    }

    private static void scanTailVersions(StorageIo.File file, long position, long size, StorageOptions options) throws IOException {
        while (size - position >= 6) {
            ByteBuffer prefix = ByteBuffer.allocate(6);
            StorageIo.readFully(file, prefix, position, options.zeroProgressLimit()); prefix.flip();
            int length = prefix.getInt(); short version = prefix.getShort();
            if (length < LogFormat.FRAME_OVERHEAD || length > LogFormat.FRAME_OVERHEAD
                    + (long) options.maxKeyBytes() + options.maxPayloadBytes()) return;
            if (version != 1) throw new StorageException(UNSUPPORTED_VERSION, "unsupported tail frame version");
            if (length > size - position) return;
            position += length;
        }
    }

    static void repair(Plan plan, Persistence disk) throws IOException {
        if (Files.size(plan.lastLog) > plan.lastBytes) disk.truncate(plan.lastLog, plan.lastBytes);
        for (Path path : plan.remove) disk.delete(path);
        disk.syncDirectory(plan.directory);
        disk.event("recovery-synced");
    }

    static long base(Path path) throws IOException {
        try { return Long.parseLong(path.getFileName().toString().substring(0, 20)); }
        catch (RuntimeException e) { throw LogFormat.corrupt("invalid segment base"); }
    }
}
