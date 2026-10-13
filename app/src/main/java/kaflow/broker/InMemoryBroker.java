package kaflow.broker;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import static kaflow.broker.BrokerException.Code.*;

/**
 * Isolated, bounded, volatile V1 core. No transport adapter, persistence or consumer API.
 * Mutation order defines partition order; timestamps do not establish ordering.
 */
public final class InMemoryBroker implements AutoCloseable {
    private static final int SNAPSHOT_RECORD_LIMIT = 128;
    private static final long SNAPSHOT_BYTE_LIMIT = 2L * 1024 * 1024;

    private final BrokerLimits limits;
    private final Clock clock;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final Object admissionLock = new Object();
    private final Runnable afterAdmission;
    // Guarded by admissionLock. Never keep this lock while waiting for mutationLock.
    private boolean admissionClosed;
    private int admittedCalls;
    private long admittedBytes;
    // Guarded by mutationLock, including all topic/partition state.
    private boolean closed;
    private final Map<TopicId, TopicState> topics = new HashMap<>();
    private int totalPartitions;
    private long retainedRecords;
    private long retainedBytes;

    public InMemoryBroker() {
        this(BrokerLimits.defaults(), Clock.systemUTC());
    }

    public InMemoryBroker(BrokerLimits limits, Clock clock) {
        this(limits, clock, () -> { });
    }

    /** Test seam: runs after reservation but before waiting for the mutation lock. */
    InMemoryBroker(BrokerLimits limits, Clock clock, Runnable afterAdmission) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.afterAdmission = Objects.requireNonNull(afterAdmission, "afterAdmission");
    }

    public void createTopic(TopicId topic, int partitionCount) {
        Objects.requireNonNull(topic, "topic");
        mutationLock.lock();
        try {
            ensureOpen();
            if (partitionCount <= 0 || partitionCount > limits.maxPartitionsPerTopic()) {
                throw failure(INVALID_ARGUMENT, "partition count outside configured bounds");
            }
            if (topics.containsKey(topic)) {
                throw failure(ALREADY_EXISTS, "topic already exists");
            }
            if (topics.size() >= limits.maxTopics()
                    || partitionCount > limits.maxTotalPartitions() - totalPartitions) {
                throw failure(RESOURCE_EXHAUSTED, "topic/partition capacity exhausted");
            }
            TopicState prepared = new TopicState(topic, partitionCount);
            topics.put(topic, prepared);
            totalPartitions = Math.addExact(totalPartitions, partitionCount);
        } finally {
            mutationLock.unlock();
        }
    }

    public AppendResult append(AppendCommand command) {
        Objects.requireNonNull(command, "command");
        int keyBytes = command.key().isPresent() ? command.key().orElseThrow().length() : 0;
        int payloadBytes = command.payload().length();
        long bytes = Math.addExact((long) keyBytes, payloadBytes);
        reserve(keyBytes, payloadBytes, bytes);
        try {
            afterAdmission.run();
            mutationLock.lock();
            try {
                ensureOpen();
                TopicState topic = topic(command.topic());
                int partitionNumber = route(command, topic);
                PartitionState partition = topic.partitions[partitionNumber];
                if (retainedRecords >= limits.maxRetainedRecords()
                        || bytes > limits.maxRetainedBytes() - retainedBytes
                        || partition.records.size() == Integer.MAX_VALUE) {
                    throw failure(RESOURCE_EXHAUSTED, "retained capacity exhausted");
                }

                // All normally fallible preparation precedes publication. Fatal VM failures
                // (e.g. OOME) are outside the recoverable rejection contract.
                long nextOffset = nextOffset(partition.nextOffset);
                long nextRecords = Math.addExact(retainedRecords, 1);
                long nextBytes = Math.addExact(retainedBytes, bytes);
                long timestamp = clock.millis();
                StoredRecord record = new StoredRecord(partition.identity, partition.nextOffset,
                        timestamp, command.key(), command.payload());
                AppendResult result = new AppendResult(partition.identity, partition.nextOffset, timestamp);
                partition.records.ensureCapacity(partition.records.size() + 1);

                partition.records.add(record);
                partition.nextOffset = nextOffset;
                retainedRecords = nextRecords;
                retainedBytes = nextBytes;
                if (command.partition().isEmpty() && command.key().isEmpty()) {
                    topic.nextUnkeyed = (partitionNumber + 1) % topic.partitions.length;
                }
                return result;
            } finally {
                mutationLock.unlock();
            }
        } finally {
            // Conservative overlap with retained accounting ends only after unlocking.
            release(bytes);
        }
    }

    private void reserve(int keyBytes, int payloadBytes, long bytes) {
        synchronized (admissionLock) {
            if (admissionClosed) {
                throw failure(CLOSED, "broker is closed");
            }
            if (keyBytes > limits.maxKeyBytes() || payloadBytes > limits.maxPayloadBytes()) {
                throw failure(INVALID_ARGUMENT, "key/payload exceeds configured maximum");
            }
            if (admittedCalls >= limits.maxAdmittedCalls()
                    || bytes > limits.maxAdmittedBytes() - admittedBytes) {
                throw failure(RESOURCE_EXHAUSTED, "append admission exhausted");
            }
            admittedCalls = Math.incrementExact(admittedCalls);
            admittedBytes = Math.addExact(admittedBytes, bytes);
        }
    }

    private void release(long bytes) {
        synchronized (admissionLock) {
            admittedCalls = Math.decrementExact(admittedCalls);
            admittedBytes = Math.subtractExact(admittedBytes, bytes);
        }
    }

    private int route(AppendCommand command, TopicState topic) {
        if (command.partition().isPresent()) {
            int explicit = command.partition().getAsInt();
            checkPartition(explicit, topic);
            return explicit;
        }
        if (command.key().isPresent()) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(command.key().orElseThrow().asReadOnlyBuffer());
                byte[] hash = digest.digest();
                long firstEight = 0;
                for (int i = 0; i < 8; i++) {
                    firstEight = (firstEight << 8) | (hash[i] & 0xffL);
                }
                return (int) Long.remainderUnsigned(firstEight, topic.partitions.length);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("JVM lacks SHA-256", e);
            }
        }
        return topic.nextUnkeyed;
    }

    static long nextOffset(long current) {
        if (current < 0 || current == Long.MAX_VALUE) {
            throw failure(RESOURCE_EXHAUSTED, "offset exhausted");
        }
        return Math.incrementExact(current);
    }

    @Override
    public void close() {
        mutationLock.lock();
        try {
            if (closed) {
                return;
            }
            // Linearization point. Admission lock can be nested only in this order.
            synchronized (admissionLock) {
                closed = true;
                admissionClosed = true;
            }
            topics.clear();
            totalPartitions = 0;
            retainedRecords = 0;
            retainedBytes = 0;
            // Outstanding reservations belong to callers' finally blocks, even after close.
        } finally {
            mutationLock.unlock();
        }
    }

    /** Bounded immutable storage inspection for same-package tests, not Fetch. */
    List<StoredRecord> snapshot(TopicPartition identity, long fromOffset, int maxRecords) {
        Objects.requireNonNull(identity, "identity");
        mutationLock.lock();
        try {
            ensureOpen();
            if (fromOffset < 0 || maxRecords < 1 || maxRecords > SNAPSHOT_RECORD_LIMIT) {
                throw failure(INVALID_ARGUMENT, "invalid snapshot bounds");
            }
            TopicState topic = topic(identity.topic());
            checkPartition(identity.partition(), topic);
            List<StoredRecord> records = topic.partitions[identity.partition()].records;
            List<StoredRecord> result = new ArrayList<>();
            long bytes = 0;
            for (long index = fromOffset; index < records.size() && result.size() < maxRecords; index++) {
                StoredRecord record = records.get((int) index);
                long size = Math.addExact((long) record.payload().length(),
                        record.key().map(ImmutableBytes::length).orElse(0));
                if (size > SNAPSHOT_BYTE_LIMIT - bytes) {
                    if (result.isEmpty()) {
                        throw failure(RESOURCE_EXHAUSTED, "record exceeds inspection byte bound");
                    }
                    break;
                }
                result.add(record);
                bytes = Math.addExact(bytes, size);
            }
            return List.copyOf(result);
        } finally {
            mutationLock.unlock();
        }
    }

    record Accounting(boolean closed, int topics, int partitions, long retainedRecords,
                      long retainedBytes, int admittedCalls, long admittedBytes) { }

    Accounting accounting() {
        mutationLock.lock();
        try {
            synchronized (admissionLock) {
                return new Accounting(closed, topics.size(), totalPartitions, retainedRecords,
                        retainedBytes, admittedCalls, admittedBytes);
            }
        } finally {
            mutationLock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw failure(CLOSED, "broker is closed");
        }
    }

    private TopicState topic(TopicId id) {
        TopicState topic = topics.get(id);
        if (topic == null) {
            throw failure(NOT_FOUND, "unknown topic");
        }
        return topic;
    }

    private static void checkPartition(int partition, TopicState topic) {
        if (partition < 0 || partition >= topic.partitions.length) {
            throw failure(INVALID_ARGUMENT, "partition does not exist in this topic");
        }
    }

    private static BrokerException failure(BrokerException.Code code, String message) {
        return new BrokerException(code, message);
    }

    private static final class TopicState {
        private final PartitionState[] partitions;
        private int nextUnkeyed;

        private TopicState(TopicId topic, int count) {
            partitions = new PartitionState[count];
            for (int i = 0; i < count; i++) {
                partitions[i] = new PartitionState(new TopicPartition(topic, i));
            }
        }
    }

    private static final class PartitionState {
        private final TopicPartition identity;
        private final ArrayList<StoredRecord> records = new ArrayList<>();
        private long nextOffset;

        private PartitionState(TopicPartition identity) {
            this.identity = identity;
        }
    }
}
