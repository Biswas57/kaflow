package kaflow.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.broker.*;
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
class LongPollingCoordinationTest {
    @TempDir Path directory;
    DurableBroker start(AtomicLong time, Persistence disk, ConsumerOptions reads, WaitOptions waits, java.util.function.Consumer<String> observer) throws Exception {
        var broker = DurableBroker.start(directory, OPTIONS, GroupOptions.defaults(), reads, waits, CLOCK, time::get, disk, observer);
        broker.awaitReady(WAIT); await(broker.createTopic(TOPIC, 3)); return broker;
    }
    static AppendCommand appendTo(int partition) { return new AppendCommand(TOPIC, OptionalInt.of(partition), Optional.empty(), EMPTY); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resolvedBarrierStillFencesWaitingOrImmediatelyActivatedRead(boolean parked) throws Exception {
        Events disk = new Events();
        var broker = start(new AtomicLong(), disk, ConsumerOptions.defaults(), WAITS, e -> {});
        try {
            var member = await(broker.join(JOIN)); CompletableFuture<FetchResult> read;
            disk.pause = "fetch-read-complete";
            if (parked) { read = broker.fetch(fetch(member, 0, 1000)); broker.awaitParked(1, WAIT); await(broker.append(appendTo(0))); }
            else { await(broker.append(appendTo(0))); read = broker.fetch(fetch(member, 0, 1000)); }
            assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, await(broker.commit(new CommitCommand(member.identity(), member.generation(), PART, 1))));
            // Pin has resolved and ownership is unchanged before the read completes.
            assertEquals(List.of(1L, 0L, 0L), await(broker.heartbeat(member.identity())).committedOffsets());
            assertEquals(1, broker.waitDiagnostics().admitted()); assertEquals(1, broker.consumerDiagnostics().reads());
            disk.proceed.countDown(); failure(UNAVAILABLE, read); clean(broker);
            assertEquals(0, await(broker.fetch(new FetchCommand(member.identity(), member.generation(), PART, 0, 1, 0))).records().get(0).offset());
        } finally { disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @ParameterizedTest @ValueSource(strings = {"join", "watchdog", "storage-failure"})
    void activatedWaitRetainsOwnershipThroughFencingAndLateReadCompletion(String cause) throws Exception {
        AtomicLong time = new AtomicLong(); Events disk = new Events();
        var broker = start(time, disk, ConsumerOptions.defaults(), WAITS, e -> {});
        try {
            var member = await(broker.join(JOIN)); var read = broker.fetch(fetch(member, 0, 1000)); broker.awaitParked(1, WAIT);
            disk.pause = "fetch-read-complete"; await(broker.append(appendTo(0))); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            if (cause.equals("join")) await(broker.join(JOIN));
            else if (cause.equals("watchdog")) {
                time.set(Duration.ofSeconds(31).toNanos()); broker.pulse(); failure(UNAVAILABLE, read);
                assertFalse(broker.shutdown(Duration.ofMillis(20)));
            } else { disk.fail = "data-forced"; failure(INTERNAL, broker.append(appendTo(1))); }
            assertEquals(1, broker.waitDiagnostics().admitted()); assertEquals(2048, broker.waitDiagnostics().metadataBytes());
            assertEquals(1, broker.consumerDiagnostics().reads()); assertTrue(broker.consumerDiagnostics().readBytes() > 0);
            disk.proceed.countDown(); failure(cause.equals("join") ? FENCED : UNAVAILABLE, read); clean(broker);
        } finally { disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @Test void fullConsumerIngressDoesNotRequireAQueueSlotToCancel() throws Exception {
        AtomicBoolean armed = new AtomicBoolean(); AtomicInteger parked = new AtomicInteger();
        CountDownLatch published = new CountDownLatch(1), release = new CountDownLatch(1);
        var reads = new ConsumerOptions(1, 1, 64L*1024*1024, 1, 2048);
        var waits = new WaitOptions(4, 4, 4, 8192, 1000, 1);
        var broker = start(new AtomicLong(), new Persistence(), reads, waits, e -> {
            if (e.equals("fetch-parked")) parked.incrementAndGet();
            if (armed.get() && e.equals("published")) { published.countDown(); hold(release); }
        });
        try {
            var member = await(broker.join(JOIN)); armed.set(true); var append = broker.append(appendTo(0));
            assertTrue(published.await(5, TimeUnit.SECONDS)); List<CompletableFuture<FetchResult>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) calls.add(broker.fetch(fetch(member, 1, 1000)));
            var immediate = broker.fetch(new FetchCommand(member.identity(), member.generation(), PART, 1, 1, 0));
            var commit = broker.commit(new CommitCommand(member.identity(), member.generation(), PART, 0));
            failure(RESOURCE_EXHAUSTED, broker.fetch(fetch(member, 1, 1000)));
            failure(RESOURCE_EXHAUSTED, broker.fetch(new FetchCommand(member.identity(), member.generation(), PART, 1, 1, 0)));
            failure(RESOURCE_EXHAUSTED, broker.commit(new CommitCommand(member.identity(), member.generation(), PART, 0)));
            for (var call : calls) assertTrue(call.cancel(false)); var heartbeat = broker.heartbeat(member.identity());
            release.countDown(); await(append); assertTrue(await(immediate).records().isEmpty()); assertEquals(0, await(commit));
            assertEquals(1, await(heartbeat).generation()); clean(broker); assertEquals(0, parked.get());
        } finally { release.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }

    @Test void hotPartitionWakeBatchesLeaveOtherPartitionsAndControlsResponsive() throws Exception {
        CountDownLatch readersStarted = new CountDownLatch(2), releaseReaders = new CountDownLatch(1);
        Persistence disk = new Persistence() { @Override void event(String point) {
            if (point.equals("fetch-read-started")) { readersStarted.countDown(); hold(releaseReaders); }
        }};
        var reads = new ConsumerOptions(2, 64, 256L*1024*1024, 8, 16384);
        var waits = new WaitOptions(64, 64, 64, 131072, 1000, 1);
        var broker = start(new AtomicLong(), disk, reads, waits, e -> {});
        try {
            var member = await(broker.join(JOIN)); List<CompletableFuture<FetchResult>> hot = new ArrayList<>();
            for (int i = 0; i < 32; i++) hot.add(broker.fetch(fetch(member, 0, 1000)));
            var quietPartition = new TopicPartition(TOPIC, 1);
            var quiet = broker.fetch(fetch(member, quietPartition, 0, 1000)); broker.awaitParked(33, WAIT);
            await(broker.append(appendTo(0))); assertTrue(readersStarted.await(5, TimeUnit.SECONDS));
            await(broker.append(appendTo(1)));
            var immediate = broker.fetch(new FetchCommand(member.identity(), member.generation(), new TopicPartition(TOPIC, 2), 0, 1, 0));
            assertEquals(1, await(broker.heartbeat(member.identity())).generation()); broker.awaitParked(0, WAIT);
            assertEquals(34, broker.consumerDiagnostics().reads()); assertEquals(32, broker.consumerDiagnostics().queuedReads());
            assertEquals(0, broker.waitDiagnostics().notifications()); assertEquals(0, broker.waitDiagnostics().timers());
            releaseReaders.countDown(); assertEquals(quietPartition, await(quiet).records().get(0).partition());
            for (var call : hot) assertEquals(PART, await(call).records().get(0).partition());
            assertTrue(await(immediate).records().isEmpty()); clean(broker);
            assertEquals(List.of(0L, 0L, 0L), await(broker.heartbeat(member.identity())).committedOffsets());
        } finally { releaseReaders.countDown(); assertTrue(broker.shutdown(WAIT)); }
    }
}
