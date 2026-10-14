package kaflow.storage;

import kaflow.broker.AppendCommand;
import kaflow.broker.ImmutableBytes;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class DurableBrokerTest {
    @TempDir Path directory;

    @Test void publishesBeforeResponseWithFixedRoutingAndRecoveredRoundRobin() throws Exception {
        Events disk = new Events(); List<String> events = disk.events;
        try (var broker = start(directory, OPTIONS, new AtomicLong(), disk, events::add)) {
            await(broker.createTopic(TOPIC, 7));
            byte[][] keys = { {}, "order-42".getBytes(java.nio.charset.StandardCharsets.UTF_8), {0,-1,-128,-61,40}, {-1} };
            int[] expected = {1,5,0,2};
            for (int i = 0; i < keys.length; i++) {
                events.clear();
                var receipt = await(broker.append(new AppendCommand(TOPIC, OptionalInt.empty(), Optional.of(ImmutableBytes.copyOf(keys[i])), EMPTY)));
                assertEquals(expected[i], receipt.partition().partition());
                assertEquals(receipt.offset()+1, broker.diagnostics().published().get(receipt.partition()));
                assertTrue(events.indexOf("checkpoint-synced") < events.indexOf("published"));
                assertEquals(receipt.timestampMillis(), await(broker.inspect(receipt.partition(), receipt.offset(), 1, 10)).get(0).timestampMillis());
            }
            assertEquals(0, await(broker.append(command())).partition().partition());
            failure(DurableException.Code.INVALID_ARGUMENT, broker.append(new AppendCommand(TOPIC, OptionalInt.of(7), Optional.of(EMPTY), EMPTY)));
            failure(DurableException.Code.NOT_FOUND, broker.append(new AppendCommand(new TopicId("missing"), OptionalInt.empty(), Optional.empty(), EMPTY)));
            assertEquals(1, await(broker.append(command())).partition().partition());
            assertEquals(6, await(broker.append(new AppendCommand(TOPIC, OptionalInt.of(6), Optional.of(EMPTY), EMPTY))).partition().partition());
            failure(DurableException.Code.ALREADY_EXISTS, broker.createTopic(TOPIC, 1));
        }
        try (var broker = DurableBroker.open(directory, OPTIONS, CLOCK)) {
            assertEquals(2, await(broker.append(command())).partition().partition());
        }
    }

    @Test void concurrentAcceptedResultsExactlyMatchContiguousRecords() throws Exception {
        var options = options(4096, 128, 2097152);
        try (var broker = DurableBroker.open(directory, options, CLOCK)) {
            await(broker.createTopic(TOPIC, 1));
            List<CompletableFuture<DurableReceipt>> receipts = new ArrayList<>();
            var callers = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                List<java.util.concurrent.Future<CompletableFuture<DurableReceipt>>> tasks = new ArrayList<>();
                for (int i = 0; i < 64; i++) { byte value = (byte) i; tasks.add(callers.submit(() -> broker.append(command(value)))); }
                for (var task : tasks) receipts.add(task.get(10, TimeUnit.SECONDS));
            } finally { callers.shutdown(); assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS)); }
            HashMap<Long,Integer> accepted = new HashMap<>();
            for (int i = 0; i < receipts.size(); i++) { var receipt = await(receipts.get(i)); assertNull(accepted.put(receipt.offset(), i)); }
            // Future completion can wake this caller before the response worker's finally releases bytes.
            // Inspection reserves the whole byte budget, so first wait for all append delivery to unwind.
            broker.awaitIdle(WAIT);
            var records = await(broker.inspect(PART, 0, 128, 2097152)); assertEquals(64, records.size());
            for (int i = 0; i < records.size(); i++) { var record = records.get(i); assertEquals(i, record.offset()); assertEquals(accepted.get((long)i).byteValue(), record.payload().toByteArray()[0]); }
            broker.awaitIdle(WAIT); assertEquals(0, broker.diagnostics().admittedCalls());
        }
    }

    @Test void cancellationRetainsCountAndBytesUntilStorageResolves() throws Exception {
        Events disk = new Events();
        try (var broker = start(directory, options(160, 2, 3), new AtomicLong(), disk, e -> {})) {
            await(broker.createTopic(TOPIC, 1)); broker.awaitIdle(WAIT);
            disk.pause = "data-written";
            var first = broker.append(command((byte)1,(byte)2,(byte)3)); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            assertTrue(first.cancel(false));
            assertEquals(3, broker.diagnostics().admittedBytes());
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.append(command((byte)4)));
            var second = broker.append(command()); second.cancel(false);
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.append(command()));
            assertEquals(2, broker.diagnostics().admittedCalls());
            disk.proceed.countDown(); broker.awaitIdle(WAIT);
            assertEquals(2, broker.diagnostics().published().get(PART));
            assertEquals(0, broker.diagnostics().admittedBytes());
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.inspect(PART, 0, 3, 10));
            assertEquals(2, await(broker.inspect(PART, 0, 3, 3)).size());
        } finally { disk.proceed.countDown(); }
    }

    @Test void zeroByteCallsExhaustCountIndependentlyOfByteBudget() throws Exception {
        Events disk = new Events();
        try (var broker = start(directory, options(160, 1, 0), new AtomicLong(), disk, e -> {})) {
            await(broker.createTopic(TOPIC, 1)); broker.awaitIdle(WAIT); disk.pause = "data-written";
            var call = broker.append(command()); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.append(command()));
            disk.proceed.countDown(); assertEquals(0, await(call).offset()); broker.awaitIdle(WAIT);
        } finally { disk.proceed.countDown(); }
    }

    @Test void watchdogStaysDegradedAndLateSuccessPublishesOnceWithoutSecondResponse() throws Exception {
        Events disk = new Events(); AtomicLong time = new AtomicLong(); CountDownLatch degraded = new CountDownLatch(1);
        try (var broker = start(directory, OPTIONS, time, disk, e -> { if(e.equals("state-DEGRADED"))degraded.countDown(); })) {
            await(broker.createTopic(TOPIC, 1)); disk.pause = "checkpoint-synced";
            var first = broker.append(command()); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            var queued = broker.append(command());
            time.set(OPTIONS.watchdog().toNanos()); broker.pulse(); assertTrue(degraded.await(5, TimeUnit.SECONDS));
            failure(DurableException.Code.UNAVAILABLE, first); failure(DurableException.Code.UNAVAILABLE, queued);
            assertFalse(broker.diagnostics().ready()); assertEquals(0, broker.diagnostics().published().get(PART));
            assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
            assertFalse(broker.shutdown(Duration.ZERO)); assertTrue(broker.diagnostics().admittedCalls() >= 1);
            assertRootLockedInAnotherJvm();
            disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT));
            assertEquals(1, broker.diagnostics().published().get(PART)); assertEquals(0, broker.diagnostics().admittedCalls());
            assertEquals(DurableBroker.State.CLOSED, broker.diagnostics().state());
        } finally { disk.proceed.countDown(); }
        try (var broker = DurableBroker.open(directory, OPTIONS, CLOCK)) { assertEquals(1, await(broker.inspect(PART, 0, 5, 10)).size()); }
    }

    private void assertRootLockedInAnotherJvm() throws Exception {
        String cp=Path.of(DirectoryLockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                +java.io.File.pathSeparator+Path.of(PartitionLog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path output=java.nio.file.Files.createTempFile("kaflow-durable-lock-", ".log");
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                DirectoryLockProbe.class.getName(),directory.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(10,TimeUnit.SECONDS));
            String message=java.nio.file.Files.readString(output).strip(); assertEquals(0,child.exitValue(),message); assertEquals("CONFLICT",message);
        } finally {
            if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}
            java.nio.file.Files.deleteIfExists(output);
        }
    }

    @Test void lateFailureRetainsOwnershipThenResolvesWithoutAutomaticReadiness() throws Exception {
        Events disk = new Events(); AtomicLong time = new AtomicLong(); CountDownLatch degraded = new CountDownLatch(1);
        try (var broker = start(directory, OPTIONS, time, disk, e -> {if(e.equals("state-DEGRADED"))degraded.countDown();})) {
            await(broker.createTopic(TOPIC, 1)); disk.pause = "data-written"; disk.fail = "data-written";
            var call = broker.append(command()); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            time.set(OPTIONS.watchdog().toNanos()); broker.pulse(); assertTrue(degraded.await(5, TimeUnit.SECONDS));
            failure(DurableException.Code.UNAVAILABLE, call); disk.proceed.countDown(); broker.awaitIdle(WAIT);
            assertEquals(DurableBroker.State.DEGRADED, broker.diagnostics().state()); assertEquals(0, broker.diagnostics().published().get(PART));
            failure(DurableException.Code.UNAVAILABLE, broker.append(command()));
        } finally { disk.proceed.countDown(); }
        try (var broker = DurableBroker.open(directory, OPTIONS, CLOCK)) { assertEquals(0, await(broker.append(command())).offset()); }
    }

    @Test void blockedResponseCallbacksCannotBlockCoordinatorAndRemainBounded() throws Exception {
        Events disk = new Events(); CountDownLatch callback = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var broker = start(directory, options(160, 2, 2097152), new AtomicLong(), disk, e -> {})) {
            await(broker.createTopic(TOPIC, 1)); broker.awaitIdle(WAIT); disk.pause = "data-written";
            var call = broker.append(command((byte) 1)); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            call.thenRun(() -> { assertEquals("kaflow-response", Thread.currentThread().getName()); callback.countDown(); try { barrier(release); } catch(IOException e){throw new RuntimeException(e);} });
            disk.proceed.countDown(); assertTrue(callback.await(5, TimeUnit.SECONDS));
            assertEquals(0, await(call).offset()); // Result is observable while delivery still owns its reservation.
            assertTrue(broker.diagnostics().ready()); assertEquals(1, broker.diagnostics().admittedCalls());
            assertEquals(1, broker.diagnostics().admittedBytes());
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.inspect(PART, 0, 128, 2097152));
            assertEquals(1, await(broker.append(command())).offset());
            release.countDown(); broker.awaitIdle(WAIT);
            assertEquals(0, broker.diagnostics().admittedBytes());
            var records = await(broker.inspect(PART, 0, 128, 2097152));
            assertEquals(2, records.size()); assertArrayEquals(new byte[] {1}, records.get(0).payload().toByteArray());
        } finally { release.countDown(); disk.proceed.countDown(); }
    }

    @Test void validationAndCapacityFailuresKeepPublishedOffsetsAndRoutingUnchanged() throws Exception {
        var tiny = new DurableOptions(new StorageOptions(80, 1, 4, 4, 1, 128, 100, 8), 1, 2, 2, 4, 20, Duration.ofSeconds(30));
        try (var broker = DurableBroker.open(directory, tiny, CLOCK)) {
            failure(DurableException.Code.INVALID_ARGUMENT, broker.createTopic(TOPIC, 3));
            await(broker.createTopic(TOPIC, 2));
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.createTopic(new TopicId("more"), 1));
            failure(DurableException.Code.INVALID_ARGUMENT, broker.append(command(new byte[5])));
            assertEquals(0, await(broker.append(command())).partition().partition());
            assertEquals(1, await(broker.append(command())).partition().partition());
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.append(command()));
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.append(command()));
            assertTrue(broker.diagnostics().ready()); assertEquals(1, broker.diagnostics().published().get(PART));
        }
    }

    @Test void independentDurableInstancesAndRepeatedCloseDoNotShareState() throws Exception {
        var a = DurableBroker.open(directory.resolve("a"), OPTIONS, CLOCK);
        var b = DurableBroker.open(directory.resolve("b"), OPTIONS, CLOCK);
        try {
            await(a.createTopic(TOPIC, 1)); await(b.createTopic(TOPIC, 1));
            assertEquals(0, await(a.append(command())).offset()); assertEquals(0, await(b.append(command())).offset());
            a.close(); a.close(); failure(DurableException.Code.UNAVAILABLE, a.append(command()));
            assertEquals(1, await(b.append(command())).offset());
        } finally { a.close(); b.close(); }
    }
}
