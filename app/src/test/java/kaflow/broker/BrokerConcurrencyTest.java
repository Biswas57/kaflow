package kaflow.broker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static kaflow.broker.BrokerException.Code.*;
import static kaflow.broker.BrokerTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class BrokerConcurrencyTest {
    record Accepted(AppendCommand command, AppendResult result) { }

    @Test
    void admissionBoundsCallsWaitingForTheMutationLock() throws Exception {
        CountDownLatch holdingLock = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch secondAdmitted = new CountDownLatch(1);
        AtomicBoolean firstClock = new AtomicBoolean(true);
        AtomicInteger admissions = new AtomicInteger();
        Clock clock = clock(() -> {
            if (firstClock.getAndSet(false)) { holdingLock.countDown(); await(releaseLock); }
            return CLOCK.millis();
        });
        BrokerLimits limits = new BrokerLimits(1, 2, 2, 0, 2, 2, 4, 10, 100);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        InMemoryBroker broker = new InMemoryBroker(limits, clock, () -> {
            if (admissions.incrementAndGet() == 2) secondAdmitted.countDown();
        });
        try {
            broker.createTopic(TOPIC, 2);
            AppendCommand input = command(bytes((byte) 1, (byte) 2));
            Future<AppendResult> first = executor.submit(() -> broker.append(input));
            await(holdingLock);
            Future<AppendResult> second = executor.submit(() -> broker.append(input));
            await(secondAdmitted);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> fails(RESOURCE_EXHAUSTED, () -> broker.append(input)));
            releaseLock.countDown();
            AppendResult left = first.get(5, TimeUnit.SECONDS);
            AppendResult right = second.get(5, TimeUnit.SECONDS);
            assertEquals(0, left.partition().partition());
            assertEquals(1, right.partition().partition());
            matches(broker, input, left);
            matches(broker, input, right);
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
            assertEquals(4, broker.accounting().retainedBytes());
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            broker.close();
        }
    }

    @Test
    void concurrentOffsetsAreContiguousAndEveryReceiptMatchesExactlyOneRecord() throws Exception {
        int threads = 8;
        int callsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try (InMemoryBroker broker = new InMemoryBroker(BrokerLimits.defaults(), CLOCK)) {
            TopicId other = new TopicId("other");
            broker.createTopic(TOPIC, 3);
            broker.createTopic(other, 3);
            List<Future<List<Accepted>>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int producer = thread;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    List<Accepted> accepted = new ArrayList<>();
                    for (int i = 0; i < callsPerThread; i++) {
                        TopicId topic = producer % 2 == 0 ? TOPIC : other;
                        ImmutableBytes payload = bytes(ByteBuffer.allocate(8).putInt(producer).putInt(i).array());
                        AppendCommand command = new AppendCommand(topic, OptionalInt.of(i % 3),
                                Optional.of(bytes((byte) producer)), payload);
                        accepted.add(new Accepted(command, broker.append(command)));
                    }
                    return accepted;
                }));
            }
            await(ready);
            start.countDown();
            Map<TopicPartition, Map<Long, Accepted>> byPartition = new HashMap<>();
            for (Future<List<Accepted>> future : futures) {
                for (Accepted accepted : future.get(5, TimeUnit.SECONDS)) {
                    AppendResult result = accepted.result();
                    assertNull(byPartition.computeIfAbsent(result.partition(), ignored -> new HashMap<>())
                            .put(result.offset(), accepted), "duplicate offset in qualified partition");
                }
            }
            int inspected = 0;
            for (var entry : byPartition.entrySet()) {
                long offset = 0;
                while (true) {
                    List<StoredRecord> page = broker.snapshot(entry.getKey(), offset, 128);
                    if (page.isEmpty()) break;
                    for (StoredRecord record : page) {
                        assertEquals(offset++, record.offset());
                        Accepted accepted = entry.getValue().remove(record.offset());
                        assertNotNull(accepted, "record without an accepted append");
                        assertEquals(accepted.result().partition(), record.partition());
                        assertEquals(accepted.result().timestampMillis(), record.timestampMillis());
                        assertEquals(accepted.command().payload(), record.payload());
                        assertEquals(accepted.command().key(), record.key());
                        inspected++;
                    }
                }
                assertTrue(entry.getValue().isEmpty(), "receipt without a record");
            }
            assertEquals(threads * callsPerThread, inspected);
            assertEquals(inspected, broker.accounting().retainedRecords());
            assertEquals(9L * inspected, broker.accounting().retainedBytes());
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
            // No assertion uses thread-start, completion, or wall-clock timestamp order.
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeWinsAgainstPreviouslyAdmittedCallWithoutWaitingForThatCaller() throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean();
        AtomicInteger timestamps = new AtomicInteger();
        Clock clock = clock(() -> { timestamps.incrementAndGet(); return CLOCK.millis(); });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (InMemoryBroker broker = new InMemoryBroker(BrokerLimits.defaults(), clock, () -> {
            if (pause.getAndSet(false)) { admitted.countDown(); await(proceed); }
        })) {
            broker.createTopic(TOPIC, 2);
            AppendCommand input = command(bytes((byte) 7));
            AppendResult accepted = broker.append(input);
            List<StoredRecord> before = broker.snapshot(accepted.partition(), 0, 128);
            pause.set(true);
            Future<AppendResult> waiting = executor.submit(() -> broker.append(input));
            await(admitted);
            // Paused admission has not changed records, offsets, routing or retained accounting.
            assertEquals(before, broker.snapshot(accepted.partition(), 0, 128));
            assertTrue(broker.snapshot(new TopicPartition(TOPIC, 1), 0, 128).isEmpty());
            assertEquals(1, broker.accounting().retainedRecords());
            assertEquals(1, broker.accounting().admittedCalls());
            assertEquals(1, broker.accounting().admittedBytes());
            // Another append can commit while the admitted caller is paused. Its expected
            // partition/offset prove admission has not consumed either routing or log position.
            AppendResult intervening = broker.append(input);
            assertEquals(new TopicPartition(TOPIC, 1), intervening.partition());
            assertEquals(0, intervening.offset());
            matches(broker, input, intervening);
            assertEquals(2, broker.accounting().retainedRecords());
            broker.close();
            broker.close();
            var closed = broker.accounting();
            assertTrue(closed.closed());
            assertEquals(0, closed.topics()); // No broker-owned topic/log references remain.
            assertEquals(0, closed.partitions());
            assertEquals(0, closed.retainedRecords());
            assertEquals(0, closed.retainedBytes());
            assertEquals(1, closed.admittedCalls()); // close must not reset someone else's reservation.
            assertEquals(1, closed.admittedBytes());
            fails(CLOSED, () -> broker.append(input));
            fails(CLOSED, () -> broker.createTopic(new TopicId("new"), 1));
            fails(CLOSED, () -> broker.snapshot(accepted.partition(), 0, 1));
            proceed.countDown();
            ExecutionException rejection = assertThrows(ExecutionException.class,
                    () -> waiting.get(5, TimeUnit.SECONDS));
            assertEquals(CLOSED, assertInstanceOf(BrokerException.class, rejection.getCause()).code());
            assertEquals(2, timestamps.get()); // Rejected call never reached pre-commit timestamp/record prep.
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
            assertEquals(0, broker.accounting().retainedRecords());
            assertEquals(input.payload(), before.get(0).payload());
            assertEquals(accepted.offset(), before.get(0).offset());
            assertThrows(UnsupportedOperationException.class, before::clear);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void appendHoldingMutationLockCommitsBeforeClose() throws Exception {
        CountDownLatch insideMutation = new CountDownLatch(1);
        CountDownLatch completeAppend = new CountDownLatch(1);
        CountDownLatch closeInvoked = new CountDownLatch(1);
        Clock clock = clock(() -> { insideMutation.countDown(); await(completeAppend); return CLOCK.millis(); });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        InMemoryBroker broker = new InMemoryBroker(BrokerLimits.defaults(), clock);
        try {
            broker.createTopic(TOPIC, 1);
            Future<AppendResult> append = executor.submit(() -> broker.append(command(bytes((byte) 9))));
            await(insideMutation);
            Future<?> close = executor.submit(() -> { closeInvoked.countDown(); broker.close(); });
            await(closeInvoked);
            completeAppend.countDown();
            close.get(5, TimeUnit.SECONDS);
            AppendResult result = append.get(5, TimeUnit.SECONDS);
            assertEquals(new TopicPartition(TOPIC, 0), result.partition());
            assertEquals(0, result.offset());
            assertEquals(CLOCK.millis(), result.timestampMillis());
            assertTrue(broker.accounting().closed());
            assertEquals(0, broker.accounting().retainedRecords());
            assertEquals(0, broker.accounting().retainedBytes());
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
            fails(CLOSED, () -> broker.append(command(EMPTY)));
        } finally {
            completeAppend.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            broker.close();
        }
    }

    private static Clock clock(java.util.function.LongSupplier millis) {
        return new Clock() {
            @Override public ZoneId getZone() { return CLOCK.getZone(); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
            @Override public long millis() { return millis.getAsLong(); }
        };
    }
}
