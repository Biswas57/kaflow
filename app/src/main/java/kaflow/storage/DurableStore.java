package kaflow.storage;

import kaflow.broker.ImmutableBytes;
import kaflow.groups.GroupId;
import kaflow.groups.GroupOptions;
import kaflow.broker.StoredRecord;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static kaflow.storage.StorageException.Code.*;

/** Writer-thread-only persistent namespace. The coordinator is the only production caller. */
final class DurableStore implements AutoCloseable {
    record TopicView(TopicId id, int count, int roundRobin) { }
    record Recovery(List<TopicView> topics, Map<TopicPartition, Long> ends, List<DurableMetadata.Group> groups, Map<TopicPartition, ReadSnapshot> snapshots,
                    Map<GroupId, List<Long>> progress) { }
    private final Thread owner = Thread.currentThread();
    private final DirectoryLease lease;
    private final DurableOptions options;
    private final GroupOptions groupOptions;
    private final Persistence disk;
    private final Clock clock;
    private final Map<TopicPartition, PartitionLog> logs = new LinkedHashMap<>();
    private final Map<TopicPartition, DurableMetadata.Checkpoint> checkpoints = new HashMap<>();
    private DurableMetadata.Catalog catalog;
    private final Map<GroupId, ConsumerProgress.Value> progress = new LinkedHashMap<>();
    private boolean failed, closed;

    static DurableStore open(Path requested, DurableOptions options, Clock clock, Persistence disk) throws IOException {
        return open(requested, options, GroupOptions.defaults(), clock, disk);
    }

    static DurableStore open(Path requested, DurableOptions options, GroupOptions groups, Clock clock, Persistence disk) throws IOException {
        Path absolute = requested.toAbsolutePath();
        if (!Files.exists(absolute)) {
            if (!Files.isDirectory(absolute.getParent())) throw new IOException("data directory parent must exist");
            Files.createDirectory(absolute); disk.syncDirectory(absolute.getParent());
        }
        DirectoryLease lease = DirectoryLease.acquire(absolute);
        DurableStore store = new DurableStore(lease, options, groups, clock, disk);
        try { disk.checkEnvironment(lease.directory); store.initialize(); return store; }
        catch (IOException | RuntimeException | Error primary) {
            try { store.close(); } catch (IOException cleanup) { primary.addSuppressed(cleanup); }
            throw primary;
        }
    }

    private DurableStore(DirectoryLease lease, DurableOptions options, GroupOptions groups, Clock clock, Persistence disk) {
        this.lease = lease; this.options = options; this.groupOptions = groups; this.clock = clock; this.disk = disk;
    }

    private void initialize() throws IOException {
        Path root = lease.directory;
        List<Path> rootEntries = DurableRecovery.entries(root, options.maxTopics() + 4);
        Path catalogPath = root.resolve("catalog");
        boolean marked = lease.hasDurableMarker();
        if (!Files.exists(catalogPath, LinkOption.NOFOLLOW_LINKS)) {
            if (marked || rootEntries.size() != 1 || !rootEntries.get(0).getFileName().toString().equals(".kaflow.lock")) {
                throw LogFormat.corrupt("nonempty directory has no durable catalog; no KF-003 migration");
            }
            lease.markDurable(); disk.syncDirectory(root); disk.event("root-marked");
            catalog = new DurableMetadata.Catalog(UUID.randomUUID(), List.of());
            disk.atomic(catalogPath, DurableMetadata.catalog(catalog), "catalog", options.storage().zeroProgressLimit());
            return;
        }
        if (!marked) throw LogFormat.corrupt("existing catalog has no durable root marker");
        regular(catalogPath);
        catalog = DurableMetadata.readCatalog(catalogPath, disk, options, groupOptions);
        List<Path> scratch = new ArrayList<>(); Path orphan = null;
        for (Path path : rootEntries) {
            String name = path.getFileName().toString();
            if (name.equals(".kaflow.lock") || name.equals("catalog")) continue;
            if (name.equals("progress") && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
            if (name.equals("catalog.tmp")) {
                regular(path); DurableMetadata.checkVersion(path, DurableMetadata.CATALOG, disk, options.storage()); scratch.add(path); continue;
            }
            if (!name.matches("t-[0-9]{8}") || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("unexpected root entry");
            int slot = Integer.parseInt(name.substring(2));
            if (slot > catalog.topics().size()) throw LogFormat.corrupt("unknown topic slot");
            if (slot == catalog.topics().size()) orphan = path;
        }
        Map<TopicPartition, DirectoryLease> owned = new LinkedHashMap<>();
        Map<TopicPartition, DurableRecovery.Plan> plans = new LinkedHashMap<>();
        List<Path> orphanEntries = orphan == null ? List.of() : orphanPlan(orphan);
        try {
            for (DurableMetadata.Topic topic : catalog.topics()) {
                Path directory = topicDirectory(topic.slot());
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("missing topic directory");
                for (Path path : DurableRecovery.entries(directory, topic.partitions() * 3)) {
                    String name = path.getFileName().toString();
                    if (!name.matches("p-[0-9]{8}(\\.pub(\\.tmp)?)?")) throw LogFormat.corrupt("unexpected topic entry");
                    int partition = Integer.parseInt(name.substring(2, 10));
                    if (partition >= topic.partitions()) throw LogFormat.corrupt("unknown partition");
                    if (name.endsWith(".tmp")) {
                        regular(path); DurableMetadata.checkVersion(path, DurableMetadata.CHECKPOINT, disk, options.storage()); scratch.add(path);
                    }
                }
                for (int p = 0; p < topic.partitions(); p++) {
                    TopicPartition id = new TopicPartition(topic.id(), p);
                    Path part = partitionDirectory(topic.slot(), p), publication = publication(topic.slot(), p);
                    if (!Files.isDirectory(part, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("missing published partition");
                    regular(publication);
                    var checkpoint = DurableMetadata.readCheckpoint(publication, catalog.store(), topic, p, disk, options.storage());
                    owned.put(id, DirectoryLease.acquire(part));
                    checkpoints.put(id, checkpoint);
                    plans.put(id, DurableRecovery.validate(part, id, checkpoint, disk, options.storage()));
                }
            }
            List<Path> progressScratch = validateProgress();
            // All authoritative metadata and published log prefixes validated before any repair.
            disk.syncDirectory(root); // Unsupported directory force cannot silently become ready.
            for (Path path : progressScratch) { disk.delete(path); disk.syncDirectory(path.getParent()); }
            for (var entry : plans.entrySet()) DurableRecovery.repair(entry.getValue(), disk);
            for (Path path : scratch) { disk.delete(path); disk.syncDirectory(path.getParent()); }
            for (Path path : orphanEntries) disk.delete(path);
            if (orphan != null) { disk.delete(orphan); disk.syncDirectory(root); }
            for (var id : plans.keySet()) {
                DirectoryLease partLease = owned.remove(id); // openOwned consumes, including on failure.
                logs.put(id, PartitionLog.openOwned(partLease, id, LogFormat.topicBytes(id), options.storage(), clock, disk.io));
            }
        } catch (IOException | RuntimeException | Error primary) {
            for (DirectoryLease partLease : owned.values()) {
                try { partLease.close(); } catch (IOException cleanup) { primary.addSuppressed(cleanup); }
            }
            throw primary;
        }
    }

    /** Only the next unpublished catalog slot can be removed, with bounded known-depth layout. */
    private List<Path> orphanPlan(Path orphan) throws IOException {
        List<Path> remove = new ArrayList<>();
        for (Path p : DurableRecovery.entries(orphan, options.maxPartitionsPerTopic() * 3)) {
            String name = p.getFileName().toString();
            if (!name.matches("p-[0-9]{8}(\\.pub(\\.tmp)?)?")
                    || Integer.parseInt(name.substring(2, 10)) >= options.maxPartitionsPerTopic()) throw LogFormat.corrupt("unsafe orphan layout");
            if (name.length() == 10) {
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("unsafe orphan partition");
                for (Path child : DurableRecovery.entries(p, 2 * options.storage().maxSegments() + 2)) {
                    regular(child); String file = child.getFileName().toString();
                    if (!file.matches("[0-9]{20}\\.(log|idx)") && !file.equals(".kaflow.lock") && !file.equals(".index-rebuild.tmp")) throw LogFormat.corrupt("unsafe orphan file");
                    if (file.endsWith(".log")) {
                        DurableMetadata.checkVersion(child, LogFormat.SEGMENT_MAGIC, disk, options.storage());
                        if (DurableRecovery.base(child) != 0) throw LogFormat.corrupt("uncatalogued topic contains noninitial segment");
                        try (StorageIo.File log = disk.io.open(child, StorageIo.Mode.READ)) {
                            // Interrupted topic creation can contain only an empty initial header.
                            // Records contradict the catalog and must never be swept as scratch.
                            long size = log.size();
                            if (size > 287) throw LogFormat.corrupt("uncatalogued topic contains data");
                            if (size >= 12) {
                                ByteBuffer prefix = ByteBuffer.allocate(12);
                                StorageIo.readFully(log, prefix, 0, options.storage().zeroProgressLimit());
                                int headerLength = prefix.getInt(8);
                                if (headerLength < 33 || headerLength > 287 || size > headerLength) throw LogFormat.corrupt("uncatalogued topic contains data");
                            }
                        }
                    }
                    remove.add(child);
                }
            } else {
                regular(p); DurableMetadata.checkVersion(p, DurableMetadata.CHECKPOINT, disk, options.storage());
                try (StorageIo.File checkpoint = disk.io.open(p, StorageIo.Mode.READ)) {
                    if (checkpoint.size() > 339) throw LogFormat.corrupt("orphan checkpoint size");
                    if (checkpoint.size() >= 80) {
                        ByteBuffer prefix = ByteBuffer.allocate(80);
                        StorageIo.readFully(checkpoint, prefix, 0, options.storage().zeroProgressLimit());
                        if (prefix.getLong(40) != 0 || prefix.getLong(48) != 0 || prefix.getLong(56) != 0
                                || prefix.getLong(72) != 0) throw LogFormat.corrupt("uncatalogued topic has publication evidence");
                    }
                }
            }
            remove.add(p);
        }
        return remove;
    }

    Recovery recovery() {
        List<TopicView> topics = new ArrayList<>(); Map<TopicPartition, Long> ends = new HashMap<>();
        for (var t : catalog.topics()) {
            int rr = 0;
            for (int p = 0; p < t.partitions(); p++) {
                var id = new TopicPartition(t.id(), p); var cp = checkpoints.get(id);
                rr = (int) ((rr + cp.unkeyed() % t.partitions()) % t.partitions()); ends.put(id, cp.end());
            }
            topics.add(new TopicView(t.id(), t.partitions(), rr));
        }
        Map<GroupId, List<Long>> positions = new HashMap<>();
        for (var group : catalog.groups()) positions.put(group.id(), progress.get(group.id()).offsets());
        return new Recovery(List.copyOf(topics), Map.copyOf(ends), catalog.groups(), snapshots(), Map.copyOf(positions));
    }

    void createTopic(TopicId id, int count) throws IOException {
        ensureOpen();
        LogFormat.topicBytes(new TopicPartition(id, 0));
        if (count < 1 || count > options.maxPartitionsPerTopic()) throw new StorageException(INVALID_ARGUMENT, "partition count");
        if (catalog.topics().stream().anyMatch(t -> t.id().equals(id))) throw new StorageException(INVALID_ARGUMENT, "duplicate topic");
        if (catalog.topics().size() >= options.maxTopics() || logs.size() + count > options.maxPartitions()) throw new StorageException(LIMIT, "topic capacity");
        // Identity/header fit is validated before creating any directory.
        if (32L + LogFormat.topicBytes(new TopicPartition(id, 0)).length + LogFormat.FRAME_OVERHEAD > options.storage().segmentBytes()) throw new StorageException(INVALID_ARGUMENT, "identity exceeds segment capacity");
        var topic = new DurableMetadata.Topic(catalog.topics().size(), id, count);
        List<DurableMetadata.Topic> nextTopics = new ArrayList<>(catalog.topics()); nextTopics.add(topic);
        var next = new DurableMetadata.Catalog(catalog.store(), nextTopics, catalog.groups(), catalog.progress());
        ByteBuffer metadata = catalogBytes(next);
        try {
            Files.createDirectory(topicDirectory(topic.slot()));
            for (int p = 0; p < count; p++) {
                TopicPartition part = new TopicPartition(id, p);
                PartitionLog log = PartitionLog.open(partitionDirectory(topic.slot(), p), part, options.storage(), clock, disk.io);
                logs.put(part, log); log.forceWritten(); disk.event("topic-data-forced");
                disk.syncDirectory(partitionDirectory(topic.slot(), p));
                var pos = log.writtenPosition(); var cp = new DurableMetadata.Checkpoint(0, 0, pos.bytes(), 0);
                disk.atomic(publication(topic.slot(), p), DurableMetadata.checkpoint(catalog.store(), topic, p, cp), "initial-checkpoint", options.storage().zeroProgressLimit());
                checkpoints.put(part, cp);
            }
            disk.syncDirectory(topicDirectory(topic.slot())); disk.syncDirectory(lease.directory); disk.event("topic-directories-synced");
            disk.atomic(lease.directory.resolve("catalog"), metadata, "catalog", options.storage().zeroProgressLimit());
            catalog = next;
        } catch (IOException | RuntimeException e) { failed = true; throw e; }
    }

    void createGroup(GroupId id, TopicId topic) throws IOException {
        ensureOpen();
        if (catalog.topics().stream().noneMatch(t -> t.id().equals(topic))) throw new StorageException(INVALID_ARGUMENT, "unknown topic");
        var existing = catalog.groups().stream().filter(g -> g.id().equals(id)).findFirst();
        if (existing.isPresent()) {
            if (!existing.get().topic().equals(topic)) throw new StorageException(INVALID_ARGUMENT, "group binding conflict");
            return;
        }
        if (catalog.groups().size() >= groupOptions.maxGroups()) throw new StorageException(LIMIT, "group count");
        var groups = new ArrayList<>(catalog.groups()); groups.add(new DurableMetadata.Group(id, topic));
        var next = new DurableMetadata.Catalog(catalog.store(), catalog.topics(), groups, catalog.progress());
        ByteBuffer metadata = catalogBytes(next);
        try {
            var binding = catalog.topics().stream().filter(t -> t.id().equals(topic)).findFirst().orElseThrow();
            var zero = ConsumerProgress.Value.zero(binding.partitions());
            if (catalog.progress()) disk.atomic(progressFile(catalog.groups().size()),
                    ConsumerProgress.encode(catalog.store(), catalog.groups().size(), binding.slot(), zero), "progress-initial", options.storage().zeroProgressLimit());
            disk.atomic(lease.directory.resolve("catalog"), metadata, "group-catalog", options.storage().zeroProgressLimit());
            catalog = next; progress.put(id, zero);
        } catch (IOException | RuntimeException e) { failed = true; throw e; }
    }

    private ByteBuffer catalogBytes(DurableMetadata.Catalog value) throws IOException {
        ByteBuffer bytes = DurableMetadata.catalog(value);
        if (bytes.remaining() > groupOptions.maxCatalogBytes()) throw new StorageException(LIMIT, "catalog byte capacity");
        return bytes;
    }

    DurableReceipt append(TopicPartition id, Optional<ImmutableBytes> key, ImmutableBytes payload, boolean unkeyed) throws IOException {
        ensureOpen(); PartitionLog log = logs.get(id);
        if (log == null) throw new StorageException(INVALID_ARGUMENT, "unknown partition");
        var topic = catalog.topics().stream().filter(t -> t.id().equals(id.topic())).findFirst().orElseThrow();
        try {
            var written = log.append(key, payload); disk.event("data-written");
            log.forceWritten(); disk.event("data-forced");
            disk.syncDirectory(partitionDirectory(topic.slot(), id.partition())); disk.event("segment-directory-synced");
            var position = log.writtenPosition();
            var cp = new DurableMetadata.Checkpoint(position.end(), position.base(), position.bytes(),
                    Math.addExact(checkpoints.get(id).unkeyed(), unkeyed ? 1 : 0));
            disk.atomic(publication(topic.slot(), id.partition()), DurableMetadata.checkpoint(catalog.store(), topic, id.partition(), cp), "checkpoint", options.storage().zeroProgressLimit());
            checkpoints.put(id, cp); disk.event("durable-complete");
            return new DurableReceipt(id, written.offset(), written.timestampMillis());
        } catch (StorageException e) {
            if (e.code() != INVALID_ARGUMENT && e.code() != LIMIT) failed = true;
            throw e;
        } catch (IOException | RuntimeException e) { failed = true; throw e; }
    }

    List<StoredRecord> inspect(TopicPartition id, long start, int count, long bytes) throws IOException {
        ensureOpen(); PartitionLog log = logs.get(id);
        if (log == null) throw new StorageException(INVALID_ARGUMENT, "unknown partition");
        return log.read(id, start, count, bytes, log.captureBoundary()).records();
    }

    Map<TopicPartition, ReadSnapshot> snapshots() {
        Map<TopicPartition, ReadSnapshot> result = new HashMap<>();
        try { for (var entry : logs.entrySet()) result.put(entry.getKey(), entry.getValue().snapshot()); }
        catch (StorageException e) { throw new IllegalStateException(e); }
        return Map.copyOf(result);
    }
    ReadSnapshot snapshot(TopicPartition partition) throws IOException { ensureOpen(); return logs.get(partition).snapshot(); }

    private Path progressDirectory() { return lease.directory.resolve("progress"); }
    private Path progressFile(int slot) { return progressDirectory().resolve(String.format(Locale.ROOT, "g-%08d.commit", slot)); }
    private DurableMetadata.Topic groupTopic(DurableMetadata.Group group) {
        return catalog.topics().stream().filter(t -> t.id().equals(group.topic())).findFirst().orElseThrow();
    }

    /** Validate all progress authority before any recovery repairs or scratch removal. */
    private List<Path> validateProgress() throws IOException {
        List<Path> scratch = new ArrayList<>();
        Path directory = progressDirectory();
        if (catalog.progress() && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("missing progress directory");
        for (int i = 0; i < catalog.groups().size(); i++) {
            var group = catalog.groups().get(i); var topic = groupTopic(group);
            var value = catalog.progress() ? ConsumerProgress.read(progressFile(i), catalog.store(), i, topic.slot(), topic.partitions(), disk, options.storage())
                    : ConsumerProgress.Value.zero(topic.partitions());
            for (int p = 0; p < topic.partitions(); p++) if (value.offsets().get(p) > checkpoints.get(new TopicPartition(topic.id(), p)).end()) throw LogFormat.corrupt("progress beyond published log");
            progress.put(group.id(), value);
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return scratch;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("progress directory type");
        for (Path path : DurableRecovery.entries(directory, 2 * (groupOptions.maxGroups() + 1))) {
            regular(path); String name = path.getFileName().toString();
            if (!name.matches("g-[0-9]{8}\\.commit(\\.tmp)?")) throw LogFormat.corrupt("unexpected progress entry");
            int slot = Integer.parseInt(name.substring(2, 10));
            if (slot > catalog.groups().size()) throw LogFormat.corrupt("progress slot");
            if (name.endsWith(".tmp")) {
                DurableMetadata.checkVersion(path, ConsumerProgress.MAGIC, disk, options.storage()); scratch.add(path); continue;
            }
            if (catalog.progress() && slot < catalog.groups().size()) continue;
            // A completed orphan/upgrade file must be a valid zero initial vector.
            int topicSlot;
            try (var file = disk.io.open(path, StorageIo.Mode.READ)) {
                if (file.size() < 60 || file.size() > 564) throw LogFormat.corrupt("orphan progress length");
                ByteBuffer prefix = ByteBuffer.allocate(40); StorageIo.readFully(file, prefix, 0, options.storage().zeroProgressLimit()); topicSlot = prefix.getInt(32);
            }
            if (topicSlot < 0 || topicSlot >= catalog.topics().size()) throw LogFormat.corrupt("orphan progress topic");
            var topic = catalog.topics().get(topicSlot);
            if (slot < catalog.groups().size() && !catalog.groups().get(slot).topic().equals(topic.id())) throw LogFormat.corrupt("upgrade binding");
            var value = ConsumerProgress.read(path, catalog.store(), slot, topicSlot, topic.partitions(), disk, options.storage());
            if (value.revision() != 0 || value.offsets().stream().anyMatch(n -> n != 0)) throw LogFormat.corrupt("uncatalogued nonzero progress");
            scratch.add(path);
        }
        return scratch;
    }

    private void enableProgress() throws IOException {
        if (catalog.progress()) return;
        if (!Files.exists(progressDirectory(), LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(progressDirectory()); disk.syncDirectory(lease.directory); }
        for (int i = 0; i < catalog.groups().size(); i++) {
            var group = catalog.groups().get(i); var topic = groupTopic(group);
            disk.atomic(progressFile(i), ConsumerProgress.encode(catalog.store(), i, topic.slot(), progress.get(group.id())),
                    "progress-initial", options.storage().zeroProgressLimit());
        }
        var next = new DurableMetadata.Catalog(catalog.store(), catalog.topics(), catalog.groups(), true);
        disk.atomic(lease.directory.resolve("catalog"), catalogBytes(next), "progress-catalog", options.storage().zeroProgressLimit());
        catalog = next;
    }

    long commit(GroupId group, TopicPartition partition, long target, boolean reset) throws IOException {
        ensureOpen();
        int slot = -1;
        for (int i = 0; i < catalog.groups().size(); i++) if (catalog.groups().get(i).id().equals(group)) slot = i;
        if (slot < 0 || !catalog.groups().get(slot).topic().equals(partition.topic()) || !checkpoints.containsKey(partition)
                || target < 0 || target > checkpoints.get(partition).end()) throw new StorageException(INVALID_ARGUMENT, "progress bounds");
        var old = progress.get(group); long previous = old.offsets().get(partition.partition());
        if (target == previous || !reset && target < previous) return previous;
        var next = old.update(partition.partition(), target); // Overflow rejects before I/O.
        try {
            enableProgress();
            var topic = groupTopic(catalog.groups().get(slot));
            disk.atomic(progressFile(slot), ConsumerProgress.encode(catalog.store(), slot, topic.slot(), next), "commit", options.storage().zeroProgressLimit());
            progress.put(group, next); return target;
        } catch (IOException | RuntimeException e) { failed = true; throw e; }
    }

    private Path topicDirectory(int slot) { return lease.directory.resolve(String.format(Locale.ROOT, "t-%08d", slot)); }
    private Path partitionDirectory(int slot, int partition) { return topicDirectory(slot).resolve(String.format(Locale.ROOT, "p-%08d", partition)); }
    private Path publication(int slot, int partition) { return topicDirectory(slot).resolve(String.format(Locale.ROOT, "p-%08d.pub", partition)); }
    private static void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("missing/nonregular metadata file");
    }
    private void ensureOpen() throws IOException {
        if (Thread.currentThread() != owner) throw new StorageException(WRONG_THREAD, "writer owns durable store");
        if (closed || failed) throw new StorageException(closed ? CLOSED : FAILED, "durable store unavailable");
    }
    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new StorageException(WRONG_THREAD, "writer owns durable close");
        if (closed) return; closed = true; IOException primary = null;
        for (PartitionLog log : logs.values()) {
            try { log.close(); } catch (IOException e) { if (primary == null) primary = e; else primary.addSuppressed(e); }
        }
        try { lease.close(); } catch (IOException e) { if (primary == null) primary = e; else primary.addSuppressed(e); }
        if (primary != null) throw primary;
    }
}
