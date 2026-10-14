package kaflow.storage;

import kaflow.broker.AppendCommand;
import kaflow.broker.ImmutableBytes;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

final class DurableTestSupport {
    static final TopicId TOPIC = new TopicId("orders");
    static final TopicPartition PART = new TopicPartition(TOPIC, 0);
    static final Duration WAIT = Duration.ofSeconds(10);
    static final ImmutableBytes EMPTY = ImmutableBytes.copyOf(new byte[0]);
    static DurableOptions options(long segment, int calls, long bytes) {
        return new DurableOptions(new StorageOptions(segment, 16, 16384, 1048576, 2, 128, 2097152, 8),
                4, 8, 16, calls, bytes, Duration.ofSeconds(30));
    }
    static final DurableOptions OPTIONS = options(160, 16, 2097152);
    static AppendCommand command(byte... bytes) {
        return new AppendCommand(TOPIC, OptionalInt.empty(), Optional.empty(), ImmutableBytes.copyOf(bytes));
    }
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    static void failure(DurableException.Code code, CompletableFuture<?> future) throws Exception {
        var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> await(future));
        assertEquals(code, ((DurableException) e.getCause()).code());
    }
    static DurableBroker start(Path directory, DurableOptions options, AtomicLong time, Persistence disk, java.util.function.Consumer<String> observer) throws Exception {
        DurableBroker broker = DurableBroker.start(directory, options, CLOCK, time::get, disk, observer);
        broker.awaitReady(WAIT); return broker;
    }
    static Path log(Path directory) { return directory.resolve("t-00000000/p-00000000/00000000000000000000.log"); }
    static Path checkpoint(Path directory) { return directory.resolve("t-00000000/p-00000000.pub"); }
    static void barrier(CountDownLatch latch) throws IOException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("test barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    static final class Events extends Persistence {
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String fail, pause;
        final IOException injected = new IOException("injected disk full: /private/internal/path");
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        @Override void event(String point) throws IOException {
            events.add(point);
            if (point.equals(pause)) { entered.countDown(); barrier(proceed); }
            if (point.equals(fail)) throw injected;
        }
    }
}
