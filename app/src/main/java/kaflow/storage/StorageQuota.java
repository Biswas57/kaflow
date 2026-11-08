package kaflow.storage;

import java.util.HashMap;
import java.util.Map;
import kaflow.broker.TopicPartition;

/** Writer updates; immutable diagnostics can be read without waiting for disk. */
final class StorageQuota {
    record View(long accountedBytes, long metadataBudget, long reservedBytes, int pendingDeletion) { }
    private final LifecycleOptions options;
    private final long metadata;
    private final Map<TopicPartition, Long> partitions = new HashMap<>();
    private long data, reserved;
    private int pending;
    private volatile View view;
    StorageQuota(LifecycleOptions options, long metadata) { this.options = options; this.metadata = metadata; publish(); }
    void reserve(TopicPartition id, long bytes) throws StorageException {
        if (bytes < 0 || id != null && bytes > options.maxPartitionBytes() - partitions.getOrDefault(id, 0L)
                || bytes > options.maxStorageBytes() - metadata - data - reserved)
            throw new StorageException(StorageException.Code.LIMIT, "managed storage quota");
        reserved = Math.addExact(reserved, bytes); publish();
    }
    void update(TopicPartition id, long bytes) {
        long previous = partitions.getOrDefault(id, 0L);
        data = Math.addExact(data, bytes - previous); partitions.put(id, bytes); publish();
    }
    void release(long bytes) { reserved -= bytes; if (reserved < 0) throw new IllegalStateException("quota release"); publish(); }
    void uncertain(TopicPartition id, long bytes) { update(id, Math.addExact(partitions.getOrDefault(id, 0L), bytes)); }
    void pending(int count) { pending = count; publish(); }
    void validateRecovered() throws StorageException { reserve(null, 0); for (var entry : partitions.entrySet()) if (entry.getKey() != null && entry.getValue() > options.maxPartitionBytes()) throw new StorageException(StorageException.Code.LIMIT, "recovered partition exceeds quota"); }
    View view() { return view; }
    private void publish() { view = new View(Math.addExact(metadata, data), metadata, reserved, pending); }
}
