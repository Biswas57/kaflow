package kaflow.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import kaflow.broker.*;
import kaflow.consumer.*;
import kaflow.groups.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.ConsumerTestSupport.GROUP;
import static kaflow.storage.ConsumerTestSupport.JOIN;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.DurableException.Code.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class LongPollingTest {
    @TempDir Path directory;
    static final WaitOptions WAITS = new WaitOptions(64, 64, 64, 131072, 1000, 8);
    static FetchCommand fetch(MembershipView member, TopicPartition partition, long offset, long wait) {
        return new FetchCommand(member.identity(), member.generation(), partition, offset, 128, 128, wait);
    }
    static FetchCommand fetch(MembershipView member, long offset, long wait) { return fetch(member, PART, offset, wait); }
    static void hold(CountDownLatch latch) { try { barrier(latch); } catch (Exception e) { throw new AssertionError(e); } }
    DurableBroker start(AtomicLong time, Persistence disk, ConsumerOptions reads, WaitOptions waits, Consumer<String> observer) throws Exception {
        var broker = DurableBroker.start(directory, OPTIONS, GroupOptions.defaults(), reads, waits, CLOCK, time::get, disk, observer);
        broker.awaitReady(WAIT); return broker;
    }
    DurableBroker start(AtomicLong time, Persistence disk) throws Exception {
        return start(time, disk, ConsumerOptions.defaults(), WAITS, e -> {});
    }
    MembershipView seed(DurableBroker broker) throws Exception { await(broker.createTopic(TOPIC, 1)); return await(broker.join(JOIN)); }
    static void clean(DurableBroker broker) throws Exception {
        broker.awaitIdle(WAIT); broker.awaitParked(0, WAIT);
        var d = broker.waitDiagnostics(); assertEquals(0, d.admitted()); assertEquals(0, d.metadataBytes());
        assertEquals(0, d.timers()); assertEquals(0, d.sessions()); assertEquals(0, d.partitions()); assertEquals(0, d.notifications());
        assertEquals(0, broker.consumerDiagnostics().reads()); assertEquals(0, broker.consumerDiagnostics().readBytes());
    }

    @Test void immediateAvailableAndInvalidBoundsPreserveContracts() throws Exception {
        Events disk = new Events();
        try (var broker = start(new AtomicLong(), disk)) {
            var member = seed(broker);
            var immediate = await(broker.fetch(new FetchCommand(member.identity(), member.generation(), PART, 0, 1, 1)));
            assertTrue(immediate.records().isEmpty()); assertEquals(0, immediate.nextOffset());
            failure(INVALID_ARGUMENT, broker.fetch(fetch(member, 0, -1)));
            failure(INVALID_ARGUMENT, broker.fetch(fetch(member, 0, 1001)));
            failure(OUT_OF_RANGE, broker.fetch(fetch(member, 1, 1000)));
            byte[] bytes = {0, -1, -128, -61, 40}; var receipt = await(broker.append(new AppendCommand(TOPIC, OptionalInt.of(0), Optional.of(EMPTY), ImmutableBytes.copyOf(bytes))));
            var result = await(broker.fetch(fetch(member, 0, 1000))); assertEquals(1, result.nextOffset());
            assertEquals(receipt.timestampMillis(), result.records().get(0).timestampMillis());
            assertArrayEquals(bytes, result.records().get(0).payload().toByteArray()); assertTrue(result.records().get(0).key().isPresent());
            assertEquals(List.of(0L), await(broker.heartbeat(member.identity())).committedOffsets()); clean(broker);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"before", "during", "after"})
    void publicationOrderedAroundRegistrationCannotBeLost(String when) throws Exception {
        CountDownLatch registering = new CountDownLatch(1), allowRegistration = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1), allowWrite = new CountDownLatch(1), persisted = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        Persistence disk = new Persistence() { @Override void event(String point) {
            if (armed.get() && point.equals("data-forced")) { writing.countDown(); hold(allowWrite); }
            if (armed.get() && point.equals("checkpoint-synced")) persisted.countDown();
        }};
        var broker = start(new AtomicLong(), disk, ConsumerOptions.defaults(), WAITS, event -> {
            if (when.equals("during") && event.equals("fetch-registering")) { registering.countDown(); hold(allowRegistration); }
        });
        try {
            var member = seed(broker); CompletableFuture<DurableReceipt> append; CompletableFuture<FetchResult> read;
            if (when.equals("before")) { append = broker.append(command((byte) 7)); await(append); read = broker.fetch(fetch(member, 0, 1000)); }
            else if (when.equals("during")) {
                armed.set(true); append = broker.append(command((byte) 7)); assertTrue(writing.await(5, TimeUnit.SECONDS));
                read = broker.fetch(fetch(member, 0, 1000)); assertTrue(registering.await(5, TimeUnit.SECONDS));
                allowWrite.countDown(); assertTrue(persisted.await(5, TimeUnit.SECONDS)); allowRegistration.countDown();
            } else {
                read = broker.fetch(fetch(member, 0, 1000)); broker.awaitParked(1, WAIT);
                armed.set(true); append = broker.append(command((byte) 7)); assertTrue(writing.await(5, TimeUnit.SECONDS));
                assertFalse(read.isDone()); assertEquals(0, broker.consumerDiagnostics().reads());
                assertEquals(0, broker.diagnostics().published().get(PART)); allowWrite.countDown();
            }
            var receipt = await(append); var result = await(read);
            assertEquals(1, result.records().size()); assertEquals(receipt.offset(), result.records().get(0).offset());
            assertArrayEquals(new byte[] {7}, result.records().get(0).payload().toByteArray()); clean(broker);
        } finally { allowWrite.countDown(); allowRegistration.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void waitExpiryUsesOriginalMonotonicTimeIncludingWrap(boolean wrap) throws Exception {
        AtomicLong time = new AtomicLong(wrap ? Long.MAX_VALUE - 50_000_000 : 0); Events disk = new Events();
        try (var broker = start(time, disk)) {
            var member = seed(broker); var read = broker.fetch(fetch(member, 0, 100)); broker.awaitParked(1, WAIT);
            time.addAndGet(99_000_000); await(broker.heartbeat(member.identity())); assertFalse(read.isDone());
            time.addAndGet(1_000_000); broker.pulse(); var result = await(read);
            assertTrue(result.records().isEmpty()); assertEquals(0, result.nextOffset()); assertEquals(0, result.publishedEnd());
            assertFalse(disk.events.contains("fetch-read-started"));
            assertEquals(List.of(0L), await(broker.heartbeat(member.identity())).committedOffsets()); clean(broker);
        }
    }

    @Test void ingressDelayConsumesWaitAndNeverResetsDeadline() throws Exception {
        AtomicLong time = new AtomicLong(); AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger parked = new AtomicInteger();
        var broker = start(time, new Persistence(), ConsumerOptions.defaults(), WAITS, event -> {
            if (event.equals("fetch-parked")) parked.incrementAndGet();
            if (armed.get() && event.equals("published")) { entered.countDown(); hold(release); }
        });
        try {
            var member = seed(broker); armed.set(true); var append = broker.append(command()); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var read = broker.fetch(fetch(member, 1, 100)); time.set(100_000_000); release.countDown(); await(append);
            assertTrue(await(read).records().isEmpty()); assertEquals(0, parked.get()); clean(broker);
        } finally { release.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @ParameterizedTest @ValueSource(strings = {"data", "cancel", "join", "close", "lease"})
    void publicationAtTimeoutUsesSerializedValidityAndAvailability(String action) throws Exception {
        AtomicLong time = new AtomicLong(); AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch published = new CountDownLatch(1), release = new CountDownLatch(1);
        var broker = start(time, new Persistence(), ConsumerOptions.defaults(), WAITS, event -> {
            if (armed.get() && event.equals("published")) { published.countDown(); hold(release); }
        });
        try {
            var member = seed(broker); var read = broker.fetch(fetch(member, 0, 100)); broker.awaitParked(1, WAIT);
            armed.set(true); var append = broker.append(command((byte) 9)); assertTrue(published.await(5, TimeUnit.SECONDS));
            time.set(action.equals("lease") ? Duration.ofSeconds(30).toNanos() : 100_000_000);
            CompletableFuture<MembershipView> join = null;
            if (action.equals("cancel")) read.cancel(false);
            if (action.equals("join")) join = broker.join(JOIN);
            if (action.equals("close")) broker.requestClose();
            release.countDown(); await(append); if (join != null) await(join);
            if (action.equals("data")) assertEquals(1, await(read).records().size());
            else if (action.equals("cancel")) assertTrue(read.isCancelled());
            else failure(action.equals("close") ? UNAVAILABLE : FENCED, read);
            clean(broker);
        } finally { release.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @ParameterizedTest @ValueSource(strings = {"leave", "generation", "expiry", "incarnation", "barrier"})
    void waitingDoesNotPreserveMembershipOrBypassCommitBarrier(String action) throws Exception {
        Events disk = new Events(); AtomicLong time = new AtomicLong();
        var broker = start(time, disk);
        try {
            var member = seed(broker); await(broker.append(command()));
            if (action.equals("incarnation")) {
                var stale = new MembershipView(new SessionIdentity(GROUP, UUID.randomUUID().toString(), member.identity().session()), member.generation(), member.leaseMillis(), member.assignments());
                failure(FENCED, broker.fetch(fetch(stale, 1, 1000))); clean(broker); return;
            }
            var read = broker.fetch(fetch(member, 1, 1000)); broker.awaitParked(1, WAIT);
            switch (action) {
                case "leave" -> await(broker.leave(member.identity()));
                case "generation" -> await(broker.join(JOIN));
                case "expiry" -> { time.set(Duration.ofSeconds(30).toNanos()); broker.pulse(); }
                case "barrier" -> {
                    disk.pause = "commit-forced";
                    var commit = broker.commit(new CommitCommand(member.identity(), member.generation(), PART, 1));
                    assertTrue(disk.entered.await(5, TimeUnit.SECONDS)); failure(UNAVAILABLE, read);
                    assertEquals(1, broker.consumerDiagnostics().barriers()); read.cancel(false);
                    assertEquals(1, broker.consumerDiagnostics().barriers()); disk.proceed.countDown(); assertEquals(1, await(commit));
                }
            }
            if (!action.equals("barrier")) failure(FENCED, read); clean(broker);
        } finally { disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @Test void heartbeatRenewsLeaseWithoutExtendingFetchDeadline() throws Exception {
        var groups = GroupMembershipTest.OPTIONS; AtomicLong time = new AtomicLong();
        try (var broker = DurableBroker.start(directory, OPTIONS, groups, ConsumerOptions.defaults(), WAITS, CLOCK, time::get, new Persistence(), e -> {})) {
            broker.awaitReady(WAIT); var member = seed(broker); var read = broker.fetch(fetch(member, 0, 180)); broker.awaitParked(1, WAIT);
            time.set(90_000_000); await(broker.heartbeat(member.identity())); time.set(180_000_000); broker.pulse();
            assertTrue(await(read).records().isEmpty()); assertEquals(1, await(broker.heartbeat(member.identity())).generation()); clean(broker);
        }
    }

    @Test void repeatedPublicationsCoalesceAndIndependentGroupsSeeIdenticalRecords() throws Exception {
        Events disk = new Events();
        try (var broker = start(new AtomicLong(), disk)) {
            var a = seed(broker); var b = await(broker.join(new JoinCommand(new GroupId("other"), TOPIC, "worker")));
            var first = broker.fetch(fetch(a, 0, 1000)); var second = broker.fetch(fetch(b, 0, 1000)); broker.awaitParked(2, WAIT);
            disk.pause = "fetch-read-started"; var receipt = await(broker.append(command((byte) 1))); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            for (int i = 2; i <= 8; i++) await(broker.append(command((byte) i)));
            assertEquals(0, broker.waitDiagnostics().notifications()); disk.proceed.countDown();
            for (var read : List.of(first, second)) {
                var result = await(read); assertEquals(1, result.publishedEnd()); assertEquals(receipt.offset(), result.records().get(0).offset());
                assertArrayEquals(new byte[] {1}, result.records().get(0).payload().toByteArray());
            }
            assertEquals(2, disk.events.stream().filter("fetch-read-started"::equals).count());
            assertEquals(List.of(0L), await(broker.heartbeat(a.identity())).committedOffsets());
            assertEquals(List.of(0L), await(broker.heartbeat(b.identity())).committedOffsets()); clean(broker);
        } finally { disk.proceed.countDown(); }
    }
}
