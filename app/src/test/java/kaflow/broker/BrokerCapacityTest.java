package kaflow.broker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static kaflow.broker.BrokerException.Code.*;
import static kaflow.broker.BrokerTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BrokerCapacityTest {
    @Test
    void defaultsAndInvalidConfiguration() {
        assertEquals(new BrokerLimits(128, 64, 1024, 16384, 1048576, 128,
                16777216, 100000, 67108864), BrokerLimits.defaults());
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(0, 1, 1, 0, 0, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 0, 1, 0, 0, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 1, 0, 0, 0, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 1, 1, -1, 0, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 1, 1, 0, -1, 1, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 1, 1, 0, 0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 1, 1, 0, 0, 1, -1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 1, 1, 0, 0, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrokerLimits(1, 1, 1, 0, 0, 1, 0, 1, -1));
    }

    @Test
    void topicCountLimitAndDuplicateCreationAreAtomic() {
        try (InMemoryBroker broker = new InMemoryBroker(new BrokerLimits(1, 3, 9, 0, 0, 1, 0, 10, 0), CLOCK)) {
            broker.createTopic(TOPIC, 2);
            fails(ALREADY_EXISTS, () -> broker.createTopic(TOPIC, 3));
            fails(RESOURCE_EXHAUSTED, () -> broker.createTopic(new TopicId("other"), 1));
            assertEquals(1, broker.accounting().topics());
            assertEquals(2, broker.accounting().partitions());
            fails(INVALID_ARGUMENT, () -> broker.append(explicit(2, EMPTY)));
        }
    }

    @Test
    void perTopicPartitionLimitAndTotalPartitionLimitAreIndependent() {
        try (InMemoryBroker broker = new InMemoryBroker(new BrokerLimits(5, 2, 3, 0, 0, 1, 0, 10, 0), CLOCK)) {
            fails(INVALID_ARGUMENT, () -> broker.createTopic(TOPIC, 0));
            fails(INVALID_ARGUMENT, () -> broker.createTopic(TOPIC, -1));
            fails(INVALID_ARGUMENT, () -> broker.createTopic(TOPIC, 3));
            assertEquals(0, broker.accounting().partitions());
            broker.createTopic(TOPIC, 2);
            fails(RESOURCE_EXHAUSTED, () -> broker.createTopic(new TopicId("other"), 2));
            broker.createTopic(new TopicId("other"), 1);
            assertEquals(3, broker.accounting().partitions());
            assertEquals(2, broker.accounting().topics());
        }
    }

    @Test
    void independentKeyAndPayloadBoundsIncludeExactMaximum() {
        try (InMemoryBroker broker = new InMemoryBroker(new BrokerLimits(1, 2, 2, 2, 3, 2, 20, 10, 100), CLOCK)) {
            broker.createTopic(TOPIC, 2);
            fails(INVALID_ARGUMENT, () -> broker.append(command(bytes(new byte[4]))));
            fails(INVALID_ARGUMENT, () -> broker.append(new AppendCommand(TOPIC, OptionalInt.empty(),
                    Optional.of(bytes(new byte[3])), EMPTY)));
            AppendCommand maximum = new AppendCommand(TOPIC, OptionalInt.of(0),
                    Optional.of(bytes(new byte[2])), bytes(new byte[3]));
            matches(broker, maximum, broker.append(maximum));
            assertEquals(5, broker.accounting().retainedBytes());
            assertEquals(0, broker.append(command(EMPTY)).partition().partition());
            assertEquals(0, broker.accounting().admittedCalls());
        }
    }

    @Test
    void zeroByteRecordsStillExhaustRetainedCountWithoutEviction() {
        try (InMemoryBroker broker = new InMemoryBroker(new BrokerLimits(1, 1, 1, 0, 0, 1, 0, 2, 0), CLOCK)) {
            broker.createTopic(TOPIC, 1);
            assertEquals(0, broker.append(command(EMPTY)).offset());
            assertEquals(1, broker.append(command(EMPTY)).offset());
            fails(RESOURCE_EXHAUSTED, () -> broker.append(command(EMPTY)));
            assertEquals(2, broker.accounting().retainedRecords());
            assertEquals(0, broker.accounting().retainedBytes());
            assertEquals(2, broker.snapshot(new TopicPartition(TOPIC, 0), 0, 128).size());
            assertEquals(0, broker.accounting().admittedCalls());
        }
    }

    @Test
    void retainedBytesChargeEachReferenceAndIncludeKeyBytes() {
        BrokerLimits limits = new BrokerLimits(1, 2, 2, 2, 2, 10, 100, 100, 6);
        try (InMemoryBroker broker = new InMemoryBroker(limits, CLOCK)) {
            broker.createTopic(TOPIC, 2);
            AppendCommand shared = new AppendCommand(TOPIC, OptionalInt.of(0),
                    Optional.of(bytes((byte) 1)), bytes((byte) 2, (byte) 3));
            broker.append(shared);
            broker.append(shared);
            fails(RESOURCE_EXHAUSTED, () -> broker.append(shared));
            fails(RESOURCE_EXHAUSTED, () -> broker.append(command(bytes((byte) 4))));
            assertEquals(6, broker.accounting().retainedBytes());
            assertEquals(2, broker.accounting().retainedRecords());
            AppendResult zero = broker.append(command(EMPTY));
            assertEquals(0, zero.partition().partition()); // Rejected unkeyed append did not advance routing.
            assertEquals(2, zero.offset());
            assertEquals(0, broker.accounting().admittedBytes());
            assertEquals(3, broker.snapshot(zero.partition(), 0, 128).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionCountAndBytesRejectImmediatelyBeforeLockAndRelease(boolean byteLimit) throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        BrokerLimits limits = new BrokerLimits(1, 2, 2, 1, 1, byteLimit ? 10 : 1,
                byteLimit ? 2 : 0, 10, 100);
        // Count case uses zero bytes; byte case has ample count and includes the key.
        AppendCommand work = byteLimit
                ? new AppendCommand(TOPIC, OptionalInt.empty(), Optional.of(bytes((byte) 1)), bytes((byte) 2))
                : command(EMPTY);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (InMemoryBroker broker = new InMemoryBroker(limits, CLOCK, () -> {
            if (pause.getAndSet(false)) { admitted.countDown(); await(proceed); }
        })) {
            broker.createTopic(TOPIC, 2);
            Future<AppendResult> first = executor.submit(() -> broker.append(work));
            await(admitted);
            assertEquals(1, broker.accounting().admittedCalls());
            assertEquals(byteLimit ? 2 : 0, broker.accounting().admittedBytes());
            assertEquals(0, broker.accounting().retainedRecords());
            // Same immutable command/reference is charged again. Deadline detects accidental waiting.
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> fails(RESOURCE_EXHAUSTED, () -> broker.append(work)));
            assertEquals(1, broker.accounting().admittedCalls());
            proceed.countDown();
            AppendResult result = first.get(5, TimeUnit.SECONDS);
            matches(broker, work, result);
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
            AppendResult next = broker.append(command(EMPTY));
            assertEquals(byteLimit ? 0 : 1, next.partition().partition());
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void clockExceptionReleasesReservationAndLeavesRoutingAndOffsetUnchanged() {
        AtomicBoolean fail = new AtomicBoolean(true);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return CLOCK.getZone(); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
            @Override public long millis() {
                if (fail.getAndSet(false)) throw new IllegalStateException("injected before commit");
                return CLOCK.millis();
            }
        };
        try (InMemoryBroker broker = new InMemoryBroker(BrokerLimits.defaults(), clock)) {
            broker.createTopic(TOPIC, 2);
            assertThrows(IllegalStateException.class, () -> broker.append(command(bytes((byte) 9))));
            assertEquals(0, broker.accounting().retainedRecords());
            assertEquals(0, broker.accounting().retainedBytes());
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
            AppendResult result = broker.append(command(bytes((byte) 9)));
            assertEquals(new TopicPartition(TOPIC, 0), result.partition());
            assertEquals(0, result.offset());
            matches(broker, command(bytes((byte) 9)), result);
        }
    }

    @Test
    void exceptionBetweenAdmissionAndLockReleasesReservation() {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (InMemoryBroker broker = new InMemoryBroker(BrokerLimits.defaults(), CLOCK, () -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("injected before lock");
        })) {
            broker.createTopic(TOPIC, 1);
            assertThrows(IllegalStateException.class, () -> broker.append(command(bytes((byte) 9))));
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
            assertEquals(0, broker.accounting().retainedRecords());
            assertEquals(0, broker.append(command(EMPTY)).offset());
        }
    }

    @Test
    void snapshotsAreBoundedImmutableAndStableAfterAppend() {
        try (InMemoryBroker broker = new InMemoryBroker()) {
            broker.createTopic(TOPIC, 1);
            TopicPartition identity = new TopicPartition(TOPIC, 0);
            for (int i = 0; i < 130; i++) broker.append(command(bytes((byte) i)));
            List<StoredRecord> first = broker.snapshot(identity, 0, 128);
            assertEquals(128, first.size());
            assertEquals(2, broker.snapshot(identity, 128, 128).size());
            assertEquals(1, broker.snapshot(identity, 129, 1).size());
            assertTrue(broker.snapshot(identity, Long.MAX_VALUE, 1).isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> first.clear());
            fails(INVALID_ARGUMENT, () -> broker.snapshot(identity, -1, 1));
            fails(INVALID_ARGUMENT, () -> broker.snapshot(identity, 0, 0));
            fails(INVALID_ARGUMENT, () -> broker.snapshot(identity, 0, 129));
            fails(NOT_FOUND, () -> broker.snapshot(new TopicPartition(new TopicId("missing"), 0), 0, 1));
            fails(INVALID_ARGUMENT, () -> broker.snapshot(new TopicPartition(TOPIC, 1), 0, 1));
            broker.append(command(EMPTY));
            assertEquals(128, first.size());
            assertEquals(bytes((byte) 127), first.get(127).payload());
        }
    }

    @Test
    void snapshotByteLimitIncludesKeysAndPagesWithoutSkipping() {
        try (InMemoryBroker broker = new InMemoryBroker()) {
            broker.createTopic(TOPIC, 1);
            ImmutableBytes large = bytes(new byte[1024 * 1024]);
            AppendCommand input = new AppendCommand(TOPIC, OptionalInt.of(0), Optional.of(bytes((byte) 1)), large);
            broker.append(input);
            broker.append(input);
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            assertEquals(1, broker.snapshot(partition, 0, 128).size());
            assertEquals(1, broker.snapshot(partition, 1, 128).get(0).offset());
            assertEquals(2L * (1024 * 1024 + 1), broker.accounting().retainedBytes());
        }
        try (InMemoryBroker broker = new InMemoryBroker()) {
            broker.createTopic(TOPIC, 1);
            for (int i = 0; i < 3; i++) broker.append(command(bytes(new byte[1024 * 1024])));
            assertEquals(2, broker.snapshot(new TopicPartition(TOPIC, 0), 0, 128).size());
        }
    }

    @Test
    void customOversizedRecordNeverEscapesInspectionByteBound() {
        int size = 2 * 1024 * 1024 + 1;
        try (InMemoryBroker broker = new InMemoryBroker(new BrokerLimits(1, 1, 1, 0, size, 1, size, 1, size), CLOCK)) {
            broker.createTopic(TOPIC, 1);
            broker.append(command(bytes(new byte[size])));
            fails(RESOURCE_EXHAUSTED, () -> broker.snapshot(new TopicPartition(TOPIC, 0), 0, 1));
        }
    }
}
