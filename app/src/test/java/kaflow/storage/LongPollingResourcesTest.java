package kaflow.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.broker.TopicPartition;
import kaflow.consumer.*;
import kaflow.groups.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.ConsumerTestSupport.JOIN;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.LongPollingTest.*;
import static kaflow.storage.DurableException.Code.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class LongPollingResourcesTest {
    @TempDir Path directory;
    DurableBroker start(AtomicLong time, Persistence disk, ConsumerOptions reads, WaitOptions waits, java.util.function.Consumer<String> observer) throws Exception {
        var broker = DurableBroker.start(directory, OPTIONS, GroupOptions.defaults(), reads, waits, CLOCK, time::get, disk, observer);
        broker.awaitReady(WAIT); return broker;
    }
    MembershipView seed(DurableBroker broker) throws Exception { await(broker.createTopic(TOPIC, 3)); return await(broker.join(JOIN)); }

    @ParameterizedTest @ValueSource(strings = {"total", "session", "partition", "bytes", "zero-bytes"})
    void independentWaitLimitsRejectAndReleaseExactlyOnce(String limit) throws Exception {
        var waits = new WaitOptions(limit.equals("total") ? 2 : 8, limit.equals("session") ? 1 : 2,
                limit.equals("partition") ? 1 : 2, limit.equals("zero-bytes") ? 0 : limit.equals("bytes") ? 2048 : 16384, 1000, 1);
        try (var broker = start(new AtomicLong(), new Persistence(), ConsumerOptions.defaults(), waits, e -> {})) {
            var a = seed(broker); var b = await(broker.join(new JoinCommand(new GroupId("other"), TOPIC, "worker")));
            var p1 = new TopicPartition(TOPIC, 1); var p2 = new TopicPartition(TOPIC, 2);
            List<CompletableFuture<FetchResult>> calls = new ArrayList<>();
            if (!limit.equals("zero-bytes")) { calls.add(broker.fetch(fetch(a, PART, 0, 1000))); broker.awaitParked(1, WAIT); }
            if (limit.equals("total")) { calls.add(broker.fetch(fetch(b, p1, 0, 1000))); broker.awaitParked(2, WAIT); }
            var request = switch (limit) {
                case "total" -> fetch(a, p2, 0, 1000);
                case "session" -> fetch(a, p1, 0, 1000);
                case "partition" -> fetch(b, PART, 0, 1000);
                default -> fetch(b, p1, 0, 1000);
            };
            failure(RESOURCE_EXHAUSTED, broker.fetch(request));
            assertEquals(calls.size() * 2048L, broker.waitDiagnostics().metadataBytes());
            assertEquals(0, broker.consumerDiagnostics().reads());
            for (var call : calls) assertTrue(call.cancel(false)); clean(broker);
        }
    }

    @Test void manyParkedCallsUseNoReaderChannelsAndRepeatedCancellationLeavesNoTimers() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        StorageIo io = (path, mode) -> { if (Thread.currentThread().getName().equals("kaflow-reader")) opens.incrementAndGet(); return StorageIo.system().open(path, mode); };
        try (var broker = start(new AtomicLong(), new Persistence(io), new ConsumerOptions(1, 1, 64L*1024*1024, 8, 16384), WAITS, e -> {})) {
            var member = seed(broker); List<CompletableFuture<FetchResult>> calls = new ArrayList<>();
            for (int i = 0; i < 64; i++) calls.add(broker.fetch(fetch(member, 0, 1000))); broker.awaitParked(64, WAIT);
            var view = broker.waitDiagnostics(); assertEquals(64, view.timers()); assertEquals(1, view.sessions()); assertEquals(1, view.partitions());
            assertEquals(0, broker.consumerDiagnostics().reads()); assertEquals(0, broker.consumerDiagnostics().activeReads());
            assertEquals(0, broker.consumerDiagnostics().queuedReads()); assertEquals(0, opens.get());
            assertEquals(1, await(broker.heartbeat(member.identity())).generation());
            for (var call : calls) call.cancel(false); clean(broker);
            for (int i = 0; i < 40; i++) {
                var read = broker.fetch(fetch(member, 0, 1000)); broker.awaitParked(1, WAIT); read.cancel(false); clean(broker);
            }
            assertEquals(0, opens.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"before", "parked", "queued", "reading", "responding"})
    void cancellationSeparatesClientCompletionFromActualCleanup(String phase) throws Exception {
        Events disk = new Events(); CountDownLatch dispatched = new CountDownLatch(phase.equals("queued") ? 2 : 1);
        CountDownLatch delivery = new CountDownLatch(1), releaseDelivery = new CountDownLatch(1);
        var reads = new ConsumerOptions(1, 2, 64L*1024*1024, 8, 16384);
        var broker = start(new AtomicLong(), disk, reads, WAITS, event -> { if (event.equals("fetch-dispatched")) dispatched.countDown(); });
        try {
            var member = seed(broker); CompletableFuture<FetchResult> call, blocker = null;
            if (phase.equals("before")) {
                var token = new FetchCancellation(); token.cancel(); call = broker.fetch(fetch(member, 0, 1000), null, token);
                assertThrows(java.util.concurrent.CancellationException.class, () -> await(call)); clean(broker); return;
            }
            if (!phase.equals("parked")) await(broker.append(command((byte) 1)));
            if (phase.equals("queued") || phase.equals("reading")) disk.pause = "fetch-read-started";
            if (phase.equals("queued")) {
                blocker = broker.fetch(new FetchCommand(member.identity(), member.generation(), PART, 0, 1, 1));
                assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            }
            call = broker.fetch(fetch(member, 0, 1000), phase.equals("responding") ? (value, error) -> { delivery.countDown(); hold(releaseDelivery); } : null);
            if (phase.equals("parked")) broker.awaitParked(1, WAIT);
            else if (phase.equals("responding")) assertTrue(delivery.await(5, TimeUnit.SECONDS));
            else { assertTrue(dispatched.await(5, TimeUnit.SECONDS)); assertTrue(disk.entered.await(5, TimeUnit.SECONDS)); }
            assertTrue(call.cancel(false));
            if (!phase.equals("parked")) {
                assertEquals(1, broker.waitDiagnostics().admitted());
                assertEquals(phase.equals("queued") ? 2 : 1, broker.consumerDiagnostics().reads());
                assertTrue(broker.consumerDiagnostics().readBytes() > 0);
            }
            releaseDelivery.countDown(); disk.proceed.countDown(); if (blocker != null) await(blocker); clean(broker);
            assertTrue(call.isCancelled()); assertEquals(List.of(0L, 0L, 0L), await(broker.heartbeat(member.identity())).committedOffsets());
        } finally { releaseDelivery.countDown(); disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @ParameterizedTest @ValueSource(strings = {"count", "bytes"})
    void wakingWithoutReadCapacityRejectsOverloadInsteadOfEmptySuccess(String limit) throws Exception {
        Events disk = new Events();
        var reads = new ConsumerOptions(1, 1, limit.equals("bytes") ? 0 : 64L*1024*1024, 8, 16384);
        var broker = start(new AtomicLong(), disk, reads, WAITS, e -> {});
        try {
            var member = seed(broker); var wait = broker.fetch(fetch(member, 0, 1000)); broker.awaitParked(1, WAIT);
            CompletableFuture<FetchResult> blocker = null;
            if (limit.equals("count")) {
                disk.pause = "fetch-read-started";
                blocker = broker.fetch(new FetchCommand(member.identity(), member.generation(), new TopicPartition(TOPIC, 1), 0, 1, 0));
                assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            }
            await(broker.append(command((byte) 1))); failure(RESOURCE_EXHAUSTED, wait); broker.awaitParked(0, WAIT);
            assertEquals(1, await(broker.heartbeat(member.identity())).generation());
            disk.proceed.countDown(); if (blocker != null) await(blocker); clean(broker);
        } finally { disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @Test void saturatedResponsesCannotPreventTimerCancellationOrControlCleanup() throws Exception {
        AtomicLong time = new AtomicLong(); CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        var waits = new WaitOptions(4, 4, 4, 8192, 1000, 1);
        var broker = start(time, new Persistence(), ConsumerOptions.defaults(), waits, e -> {});
        try {
            var member = seed(broker); List<CompletableFuture<FetchResult>> responses = new ArrayList<>();
            for (int i = 0; i < 2; i++) responses.add(broker.fetch(fetch(member, 0, 1), (value, error) -> { entered.countDown(); hold(release); }));
            broker.awaitParked(2, WAIT); time.set(1_000_000); broker.pulse(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, broker.waitDiagnostics().admitted()); assertEquals(0, broker.consumerDiagnostics().reads());
            List<CompletableFuture<FetchResult>> canceled = new ArrayList<>();
            for (int i = 0; i < 2; i++) canceled.add(broker.fetch(fetch(member, 0, 1000))); broker.awaitParked(2, WAIT);
            failure(RESOURCE_EXHAUSTED, broker.fetch(fetch(member, 0, 1000)));
            for (var call : canceled) call.cancel(false); broker.awaitParked(0, WAIT);
            assertEquals(0, broker.waitDiagnostics().timers()); assertEquals(4, broker.waitDiagnostics().admitted());
            assertEquals(1, await(broker.heartbeat(member.identity())).generation());
            release.countDown(); for (var response : responses) assertTrue(await(response).records().isEmpty()); clean(broker);
        } finally { release.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @ParameterizedTest @ValueSource(strings = {"close", "storage-failure", "read-watchdog"})
    void lifecycleDrainsWaitersButNeverReleasesBlockedReadersEarly(String cause) throws Exception {
        AtomicLong time = new AtomicLong(); Events disk = new Events(); var broker = start(time, disk, ConsumerOptions.defaults(), WAITS, e -> {});
        try {
            var member = seed(broker); var wait = broker.fetch(fetch(member, 0, 1000)); broker.awaitParked(1, WAIT);
            CompletableFuture<FetchResult> read = null;
            switch (cause) {
                case "close" -> broker.requestClose();
                case "storage-failure" -> { disk.fail = "data-forced"; failure(INTERNAL, broker.append(command())); }
                case "read-watchdog" -> {
                    disk.pause = "fetch-read-started";
                    read = broker.fetch(new FetchCommand(member.identity(), member.generation(), new TopicPartition(TOPIC, 1), 0, 1, 0));
                    assertTrue(disk.entered.await(5, TimeUnit.SECONDS)); time.set(Duration.ofSeconds(31).toNanos()); broker.pulse(); failure(UNAVAILABLE, read);
                }
            }
            failure(UNAVAILABLE, wait); broker.awaitParked(0, WAIT);
            if (read != null) { assertFalse(broker.shutdown(Duration.ofMillis(20))); assertEquals(1, broker.consumerDiagnostics().reads()); }
            disk.proceed.countDown(); clean(broker);
        } finally { disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @Test void waitOptionsRejectUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1025, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1, 2, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1, 1, 2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1, 1, 1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1, 1, 1, 2097153, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1, 1, 1, 1, 120001, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaitOptions(1, 1, 1, 1, 1, 33));
    }
}
