package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import kaflow.broker.ImmutableBytes;
import kaflow.broker.StoredRecord;
import kaflow.broker.TopicPartition;

/** Writer-issued immutable metadata. Handles own independent positional readers, never a log owner. */
final class ReadSnapshot {
    record Segment(long base, long end, long bytes, int checksum) { }
    final TopicPartition partition;
    final long end;
    private final Path directory;
    private final List<Segment> segments;
    private final StorageOptions options;
    private final StorageIo io;

    ReadSnapshot(TopicPartition partition, long end, Path directory, List<Segment> segments,
                 StorageOptions options, StorageIo io) {
        this.partition = partition; this.end = end; this.directory = directory;
        this.segments = List.copyOf(segments); this.options = options; this.io = io;
    }
    Handle acquire() { return new Handle(this); }

    static final class Handle implements AutoCloseable {
        private ReadSnapshot snapshot;
        Handle(ReadSnapshot snapshot) { this.snapshot = snapshot; }
        PartitionLog.ReadBatch read(long start, int count, long budget) throws IOException {
            ReadSnapshot s = snapshot;
            if (s == null) throw new IllegalStateException("released read handle");
            return s.read(start, count, budget);
        }
        @Override public void close() { snapshot = null; }
    }

    private PartitionLog.ReadBatch read(long start, int count, long budget) throws IOException {
        if (start < 0 || start > end || count < 1 || count > options.maxReadRecords()
                || budget < 0 || budget > options.maxReadBytes()) throw new StorageException(StorageException.Code.INVALID_ARGUMENT, "read bounds");
        if (start == end) return new PartitionLog.ReadBatch(List.of(), start);
        byte[] topic = LogFormat.topicBytes(partition);
        List<StoredRecord> records = new ArrayList<>(); long next = start, used = 0;
        int low = 0, high = segments.size() - 1;
        while (low < high) { int mid = (low + high + 1) >>> 1; if (segments.get(mid).base <= start) low = mid; else high = mid - 1; }
        while (next < end && records.size() < count) {
            Segment segment = segments.get(low++);
            try (StorageIo.File log = io.open(path(segment.base, ".log"), StorageIo.Mode.READ);
                 StorageIo.File index = io.open(path(segment.base, ".idx"), StorageIo.Mode.READ)) {
                if (log.size() < segment.bytes) throw LogFormat.corrupt("captured segment truncated");
                var header = LogFormat.readHeader(log, partition, topic, segment.base, segment.bytes, options);
                if (header.checksum() != segment.checksum) throw LogFormat.corrupt("captured header changed");
                ByteBuffer indexHeader = ByteBuffer.allocate(LogFormat.INDEX_HEADER);
                StorageIo.readFully(index, indexHeader, 0, options.zeroProgressLimit());
                if (!Arrays.equals(indexHeader.array(), LogFormat.indexHeader(segment.base, segment.checksum, options.indexInterval()).array())) throw LogFormat.corrupt("index header changed");
                long ordinal = (next - segment.base) / options.indexInterval();
                long indexed = segment.base + ordinal * options.indexInterval();
                var seek = LogFormat.readIndexEntry(index, ordinal, indexed, header.length(), segment.bytes, options);
                long offset = indexed, position = seek.position();
                while (offset < segment.end && offset < end && records.size() < count) {
                    var frame = LogFormat.readFrame(log, partition, position, segment.bytes, offset, options);
                    if (offset == indexed && frame.length() != seek.frameLength()) throw LogFormat.corrupt("index frame mismatch");
                    if (offset >= next) {
                        var record = frame.record(); long bytes = (long) record.payload().length() + record.key().map(ImmutableBytes::length).orElse(0);
                        if (bytes > budget - used) {
                            if (records.isEmpty()) throw new StorageException(StorageException.Code.BUDGET_TOO_SMALL, "first record exceeds budget");
                            return new PartitionLog.ReadBatch(records, next);
                        }
                        records.add(record); used += bytes; next = offset + 1;
                    }
                    position += frame.length(); offset++;
                }
            }
        }
        return new PartitionLog.ReadBatch(records, next);
    }
    private Path path(long base, String suffix) { return directory.resolve(String.format(Locale.ROOT, "%020d%s", base, suffix)); }
}
