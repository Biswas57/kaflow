package kaflow.storage;

import kaflow.broker.ImmutableBytes;
import kaflow.broker.StoredRecord;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static kaflow.storage.StorageException.Code.*;

/**
 * One thread-owned partition directory. Completed writes are neither forced nor durable.
 * This component has no network service, coordinator adapter, consumer state or repair policy.
 */
public final class PartitionLog implements AutoCloseable {
    public enum State { OPEN, FAILED, CLOSED }

    public record CompletedWrite(TopicPartition partition, long offset, long timestampMillis,
                                 long completedWriteEnd) { }

    public record ReadBatch(List<StoredRecord> records, long nextOffset) {
        public ReadBatch { records = List.copyOf(records); }
    }

    /** Only this instance can issue its own boundary handles; not a durability watermark. */
    public static final class ReadBoundary {
        private final Object owner;
        private final TopicPartition partition;
        private final long endOffset;

        private ReadBoundary(Object owner, TopicPartition partition, long endOffset) {
            this.owner = owner;
            this.partition = partition;
            this.endOffset = endOffset;
        }

        public TopicPartition partition() { return partition; }
        public long endOffset() { return endOffset; }
    }

    private final Thread owner = Thread.currentThread();
    private final Object boundaryOwner = new Object();
    private final TopicPartition identity;
    private final byte[] topic;
    private final int headerLength;
    private final StorageOptions options;
    private final Clock clock;
    private final StorageIo io;
    private final DirectoryLease lease;
    private final List<Segment> segments = new ArrayList<>();
    private StorageIo.File activeLog;
    private StorageIo.File activeIndex;
    private long completedEnd;
    private State state = State.OPEN;
    private IOException failure;

    public static PartitionLog open(Path directory, TopicPartition identity, StorageOptions options,
                                    Clock clock) throws IOException {
        return open(directory, identity, options, clock, StorageIo.system());
    }

    static PartitionLog open(Path directory, TopicPartition identity, StorageOptions options,
                             Clock clock, StorageIo io) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(io, "io");
        byte[] topic = LogFormat.topicBytes(identity);
        if (32L + topic.length + LogFormat.FRAME_OVERHEAD > options.segmentBytes()) {
            throw new StorageException(INVALID_ARGUMENT, "segment cannot contain this identity and an empty frame");
        }
        DirectoryLease lease = DirectoryLease.acquire(directory);
        PartitionLog log = new PartitionLog(identity, topic, options, clock, io, lease);
        try {
            log.initialize();
            return log;
        } catch (IOException | RuntimeException | Error primary) {
            try { log.close(); } catch (IOException cleanup) { primary.addSuppressed(cleanup); }
            throw primary;
        }
    }

    private PartitionLog(TopicPartition identity, byte[] topic, StorageOptions options,
                         Clock clock, StorageIo io, DirectoryLease lease) {
        this.identity = identity;
        this.topic = topic;
        this.headerLength = 32 + topic.length;
        this.options = options;
        this.clock = clock;
        this.io = io;
        this.lease = lease;
    }

    private void initialize() throws IOException {
        List<Long> bases = new ArrayList<>();
        Set<Long> indexes = new HashSet<>();
        int entries = 0;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(lease.directory)) {
            for (Path path : paths) {
                if (++entries > 2 * options.maxSegments() + 2) throw new StorageException(LIMIT, "too many directory entries");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("nonregular storage entry");
                String name = path.getFileName().toString();
                if (name.equals(".kaflow.lock") || name.equals(".index-rebuild.tmp")) continue;
                if (!name.matches("[0-9]{20}\\.(log|idx)")) throw LogFormat.corrupt("unexpected storage filename");
                long base;
                try { base = Long.parseLong(name.substring(0, 20)); }
                catch (NumberFormatException e) { throw LogFormat.corrupt("segment filename overflow"); }
                if (name.endsWith(".log")) {
                    if (bases.size() >= options.maxSegments()) throw new StorageException(LIMIT, "too many segments");
                    bases.add(base);
                } else {
                    indexes.add(base);
                }
            }
        }
        bases.sort(Comparator.naturalOrder());
        if (!new HashSet<>(bases).containsAll(indexes)) throw LogFormat.corrupt("orphan index");
        if (bases.isEmpty()) {
            createSegment(0);
            return;
        }
        long next = 0;
        for (long base : bases) {
            if (base != next) throw LogFormat.corrupt("segment offset gap");
            Segment segment = validateSegment(base, indexes.contains(base));
            if (segment.end == base && base != bases.get(bases.size() - 1)) throw LogFormat.corrupt("empty nonfinal segment");
            segments.add(segment);
            next = segment.end;
        }
        // Validate all authoritative bytes before touching any derived index.
        for (Segment segment : segments) {
            if (!segment.indexValid) rebuildIndex(segment);
        }
        Files.deleteIfExists(lease.directory.resolve(".index-rebuild.tmp"));
        completedEnd = next;
        Segment last = segments.get(segments.size() - 1);
        activeLog = io.open(logPath(last.base), StorageIo.Mode.EXISTING);
        activeIndex = io.open(indexPath(last.base), StorageIo.Mode.EXISTING);
    }

    private Segment validateSegment(long base, boolean hasIndex) throws IOException {
        try (StorageIo.File log = io.open(logPath(base), StorageIo.Mode.READ);
             StorageIo.File index = hasIndex ? io.open(indexPath(base), StorageIo.Mode.READ) : null) {
            long size = log.size();
            if (size > options.segmentBytes()) throw new StorageException(LIMIT, "existing segment exceeds configured limit");
            LogFormat.Header header = LogFormat.readHeader(log, identity, topic, base, size, options);
            ByteBuffer expectedHeader = LogFormat.indexHeader(base, header.checksum(), options.indexInterval());
            boolean valid = index != null && bytesEqual(index, 0, expectedHeader);
            long offset = base;
            long position = header.length();
            long indexEntries = 0;
            while (position < size) {
                LogFormat.Frame frame = LogFormat.readFrame(log, identity, position, size, offset, options);
                if ((offset - base) % options.indexInterval() == 0) {
                    if (valid) valid = bytesEqual(index, indexPosition(indexEntries), LogFormat.indexEntry(offset, position, frame.length()));
                    indexEntries++;
                }
                position = Math.addExact(position, frame.length());
                offset = Math.incrementExact(offset);
            }
            if (valid) valid = index.size() == indexPosition(indexEntries);
            return new Segment(base, offset, size, header.checksum(), indexEntries, valid);
        }
    }

    private boolean bytesEqual(StorageIo.File file, long position, ByteBuffer expected) throws IOException {
        long size = file.size();
        if (position > size || expected.remaining() > size - position) return false;
        ByteBuffer actual = ByteBuffer.allocate(expected.remaining());
        StorageIo.readFully(file, actual, position, options.zeroProgressLimit());
        return Arrays.equals(actual.array(), expected.array());
    }

    private void rebuildIndex(Segment segment) throws IOException {
        Path temp = lease.directory.resolve(".index-rebuild.tmp");
        Files.deleteIfExists(temp); // Derived scratch only, never a log or lock file.
        try (StorageIo.File log = io.open(logPath(segment.base), StorageIo.Mode.READ);
             StorageIo.File index = io.open(temp, StorageIo.Mode.CREATE)) {
            StorageIo.writeFully(index, LogFormat.indexHeader(segment.base, segment.headerChecksum, options.indexInterval()),
                    0, options.zeroProgressLimit());
            long offset = segment.base;
            long position = headerLength;
            long entries = 0;
            while (position < segment.bytes) {
                LogFormat.Frame frame = LogFormat.readFrame(log, identity, position, segment.bytes, offset, options);
                if ((offset - segment.base) % options.indexInterval() == 0) {
                    StorageIo.writeFully(index, LogFormat.indexEntry(offset, position, frame.length()),
                            indexPosition(entries++), options.zeroProgressLimit());
                }
                position = Math.addExact(position, frame.length());
                offset = Math.incrementExact(offset);
            }
        }
        Files.move(temp, indexPath(segment.base), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        segment.indexValid = true;
    }

    private void createSegment(long base) throws IOException {
        ByteBuffer header = LogFormat.segmentHeader(identity, topic, base);
        int checksum = header.getInt(header.limit() - 4);
        activeLog = io.open(logPath(base), StorageIo.Mode.CREATE);
        StorageIo.writeFully(activeLog, header, 0, options.zeroProgressLimit());
        activeIndex = io.open(indexPath(base), StorageIo.Mode.CREATE);
        StorageIo.writeFully(activeIndex, LogFormat.indexHeader(base, checksum, options.indexInterval()),
                0, options.zeroProgressLimit());
        segments.add(new Segment(base, base, headerLength, checksum, 0, true));
    }

    public CompletedWrite append(Optional<ImmutableBytes> key, ImmutableBytes payload) throws IOException {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        int length = LogFormat.frameLength(key, payload, options);
        if (length > options.segmentBytes() - headerLength) throw new StorageException(LIMIT, "frame exceeds empty segment capacity");
        long next = nextOffset(completedEnd);
        Segment current = segments.get(segments.size() - 1);
        boolean rotate = length > options.segmentBytes() - current.bytes;
        if (rotate && segments.size() >= options.maxSegments()) throw new StorageException(LIMIT, "segment count exhausted");
        long timestamp = clock.millis();
        ByteBuffer frame = LogFormat.frame(key, payload, completedEnd, timestamp, length);
        CompletedWrite receipt = new CompletedWrite(identity, completedEnd, timestamp, next);
        long position = rotate ? headerLength : current.bytes;
        long base = rotate ? completedEnd : current.base;
        boolean indexed = (completedEnd - base) % options.indexInterval() == 0;
        ByteBuffer entry = indexed ? LogFormat.indexEntry(completedEnd, position, length) : null;
        try {
            if (rotate) {
                closeActive();
                createSegment(completedEnd);
                current = segments.get(segments.size() - 1);
            }
            if (activeLog.size() != current.bytes || activeIndex.size() != indexPosition(current.indexEntries)) {
                throw LogFormat.corrupt("active file length changed outside its owner");
            }
            StorageIo.writeFully(activeLog, frame, position, options.zeroProgressLimit());
            if (indexed) StorageIo.writeFully(activeIndex, entry, indexPosition(current.indexEntries), options.zeroProgressLimit());
            // Publish only after all required writes complete; these stores cannot throw.
            current.bytes = position + length;
            current.end = next;
            if (indexed) current.indexEntries++;
            completedEnd = next;
            return receipt;
        } catch (IOException e) {
            fail(e);
            throw e;
        }
    }

    static long nextOffset(long current) throws StorageException {
        if (current < 0 || current == Long.MAX_VALUE) throw new StorageException(LIMIT, "storage offset overflow");
        return Math.incrementExact(current);
    }

    public ReadBoundary captureBoundary() throws StorageException {
        checkOwner();
        if (state == State.CLOSED) throw new StorageException(CLOSED, "storage is closed");
        return new ReadBoundary(boundaryOwner, identity, completedEnd);
    }

    public State state() throws StorageException {
        checkOwner();
        return state;
    }

    public ReadBatch read(TopicPartition partition, long start, int maxRecords, long maxBytes,
                          ReadBoundary boundary) throws IOException {
        ensureOpen();
        if (!identity.equals(partition)) throw new StorageException(WRONG_IDENTITY, "read targets another partition");
        if (boundary == null || boundary.owner != boundaryOwner || start < 0
                || maxRecords < 1 || maxRecords > options.maxReadRecords()
                || maxBytes < 0 || maxBytes > options.maxReadBytes()) {
            throw new StorageException(INVALID_ARGUMENT, "invalid read bounds or foreign boundary");
        }
        if (start >= boundary.endOffset) return new ReadBatch(List.of(), start);
        List<StoredRecord> result = new ArrayList<>();
        long next = start;
        long bytes = 0;
        try {
            int segmentNumber = findSegment(start);
            while (next < boundary.endOffset && result.size() < maxRecords) {
                Segment segment = segments.get(segmentNumber++);
                try (StorageIo.File log = io.open(logPath(segment.base), StorageIo.Mode.READ);
                     StorageIo.File index = io.open(indexPath(segment.base), StorageIo.Mode.READ)) {
                    long ordinal = (next - segment.base) / options.indexInterval();
                    long indexedOffset = Math.addExact(segment.base, Math.multiplyExact(ordinal, options.indexInterval()));
                    LogFormat.IndexEntry seek = LogFormat.readIndexEntry(index, ordinal, indexedOffset,
                            headerLength, segment.bytes, options);
                    long offset = seek.offset();
                    long position = seek.position();
                    while (offset < segment.end && offset < boundary.endOffset && result.size() < maxRecords) {
                        LogFormat.Frame frame = LogFormat.readFrame(log, identity, position, segment.bytes, offset, options);
                        if (offset == indexedOffset && frame.length() != seek.frameLength()) throw LogFormat.corrupt("index frame length mismatch");
                        if (offset >= next) {
                            StoredRecord record = frame.record();
                            long size = (long) record.payload().length() + record.key().map(ImmutableBytes::length).orElse(0);
                            if (size > maxBytes - bytes) {
                                if (result.isEmpty()) throw new StorageException(BUDGET_TOO_SMALL, "next record exceeds requested byte budget");
                                return new ReadBatch(result, next);
                            }
                            result.add(record);
                            bytes += size;
                            next = offset + 1;
                        }
                        position += frame.length();
                        offset++;
                    }
                }
            }
            return new ReadBatch(result, next);
        } catch (StorageException e) {
            // A budget rejection alone is harmless. A suppressed channel-close failure
            // still makes the owner unsafe, without replacing the original rejection.
            if (e.code() != BUDGET_TOO_SMALL || e.getSuppressed().length != 0) fail(e);
            throw e;
        } catch (IOException e) {
            fail(e);
            throw e;
        }
    }

    private int findSegment(long offset) {
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (segments.get(middle).base <= offset) low = middle; else high = middle - 1;
        }
        return low;
    }

    private void checkOwner() throws StorageException {
        if (Thread.currentThread() != owner) throw new StorageException(WRONG_THREAD, "operation requires the opening thread");
    }

    private void ensureOpen() throws StorageException {
        checkOwner();
        if (state == State.CLOSED) throw new StorageException(CLOSED, "storage is closed");
        if (state == State.FAILED) throw new StorageException(FAILED, "storage owner failed; reopen requires validation", failure);
    }

    private void fail(IOException cause) {
        state = State.FAILED;
        if (failure == null) failure = cause;
    }

    private static long indexPosition(long entries) {
        return Math.addExact(LogFormat.INDEX_HEADER, Math.multiplyExact(entries, LogFormat.INDEX_ENTRY));
    }

    private Path logPath(long base) { return lease.directory.resolve(fileName(base, ".log")); }
    private Path indexPath(long base) { return lease.directory.resolve(fileName(base, ".idx")); }
    private static String fileName(long base, String suffix) { return String.format(Locale.ROOT, "%020d%s", base, suffix); }

    private void closeActive() throws IOException {
        IOException primary = null;
        if (activeLog != null) {
            try { activeLog.close(); } catch (IOException e) { primary = e; }
            activeLog = null;
        }
        if (activeIndex != null) {
            try { activeIndex.close(); } catch (IOException e) {
                if (primary == null) primary = e; else primary.addSuppressed(e);
            }
            activeIndex = null;
        }
        if (primary != null) throw primary;
    }

    @Override
    public void close() throws IOException {
        checkOwner();
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        IOException primary = null;
        try { closeActive(); } catch (IOException e) { primary = e; }
        try { lease.close(); } catch (IOException e) {
            if (primary == null) primary = e; else primary.addSuppressed(e);
        }
        segments.clear();
        if (primary != null) throw primary;
    }

    private static final class Segment {
        final long base;
        long end;
        long bytes;
        final int headerChecksum;
        long indexEntries;
        boolean indexValid;

        Segment(long base, long end, long bytes, int headerChecksum, long indexEntries, boolean indexValid) {
            this.base = base;
            this.end = end;
            this.bytes = bytes;
            this.headerChecksum = headerChecksum;
            this.indexEntries = indexEntries;
            this.indexValid = indexValid;
        }
    }
}
