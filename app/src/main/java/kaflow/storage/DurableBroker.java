package kaflow.storage;

import kaflow.broker.AppendCommand;
import kaflow.groups.*;
import kaflow.consumer.*;
import java.util.function.BiConsumer;
import kaflow.broker.ImmutableBytes;
import kaflow.broker.StoredRecord;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static kaflow.storage.DurableException.Code.*;

/** Bounded coordinator + one storage owner. No disk or response callback runs on coordination. */
public final class DurableBroker implements AutoCloseable {
    public enum State { INITIALIZING, READY, DEGRADED, CLOSING, CLOSED }
    public record Diagnostics(State state, boolean ready, int admittedCalls, long admittedBytes,
                              int queuedCalls, long activeOperation, boolean watchdogExpired,
                              Map<TopicPartition, Long> published) { }
    private enum Kind { CREATE, APPEND, INSPECT, GROUP, COMMIT, RESET, CLOSE }
    private enum ControlKind { JOIN, HEARTBEAT, LEAVE, VALIDATE }
    public record MembershipDiagnostics(int groups, int members, int timerEntries, int admittedControls,
                                        long admittedBytes, int pendingJoins, int queuedControls) { }
    private final ConsumerOptions consumerOptions;
    private final WaitOptions waitOptions;
    private final FetchWaiters<ConsumerCall<?>> waiters = new FetchWaiters<>();
    private final Map<SessionIdentity, Integer> waitSessions = new HashMap<>();
    private final Map<TopicPartition, Integer> waitPartitions = new HashMap<>();
    private int admittedWaits;
    private long waitCharge;
    public record WaitDiagnostics(int admitted, long metadataBytes, int parked, int timers,
                                  int sessions, int partitions, int notifications) { }
    private volatile WaitDiagnostics waitView = new WaitDiagnostics(0, 0, 0, 0, 0, 0, 0);
    private enum FetchPhase { ADMITTED, WAITING, RUNNABLE, READING, RESPONDING, TERMINAL }
    private final ArrayBlockingQueue<ConsumerCall<?>> consumers;
    private final ArrayBlockingQueue<ReadDone> readCompletions;
    private final ThreadPoolExecutor readers, consumerResponses;
    private final Map<Long, ConsumerCall<?>> reading = new HashMap<>();
    private final Map<TopicPartition, ReadSnapshot> snapshots = new HashMap<>();
    private final Map<GroupId, List<Long>> progress = new HashMap<>();
    private final Persistence disk;
    private int admittedReads, admittedProgress;
    private long readCharge, progressCharge;
    private volatile int activeReads, barriers;
    public record ConsumerDiagnostics(int reads, long readBytes, int progressCalls, long progressBytes,
                                      int activeReads, int barriers, int queuedReads) { }
    private final GroupOptions groupOptions;
    private final GroupMembership membership;
    private final ArrayBlockingQueue<Control<?>> controls;
    private final ThreadPoolExecutor controlResponses;
    private int admittedControls, pendingJoins;
    private long controlBytes;
    private volatile int groupCount, memberCount;
    private final Object epoch = new Object();
    private final Object gate = new Object();
    private final DurableOptions options;
    private final LongSupplier nanos;
    private final Consumer<String> observer;
    private final ArrayBlockingQueue<Work<?>> requests;
    private final ArrayBlockingQueue<Work<?>> writerQueue = new ArrayBlockingQueue<>(1);
    private final AtomicReference<Completion> completion = new AtomicReference<>();
    private final Semaphore wake = new Semaphore(0);
    private final ThreadPoolExecutor responses;
    private final CompletableFuture<Void> initialized = new CompletableFuture<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final Map<TopicId, TopicState> topics = new HashMap<>();
    private final Map<TopicPartition, Long> published = new HashMap<>();
    private volatile Map<TopicPartition, Long> publishedView = Map.of();
    private volatile State state = State.INITIALIZING;
    private volatile boolean closeRequested, watchdogExpired, readWatchdogExpired;
    private volatile long activeId;
    private int admittedCalls;
    private long admittedBytes, sequence;
    private Work<?> active;
    private boolean initializing = true, closeDispatched;
    private long started;

    public static DurableBroker open(Path directory, DurableOptions options, Clock clock) throws IOException {
        return open(directory, options, GroupOptions.defaults(), clock);
    }

    public static DurableBroker open(Path directory, DurableOptions options, GroupOptions groups, Clock clock) throws IOException {
        return open(directory, options, groups, ConsumerOptions.defaults(), clock);
    }

    public static DurableBroker open(Path directory, DurableOptions options, GroupOptions groups, ConsumerOptions consumers, Clock clock) throws IOException {
        return open(directory, options, groups, consumers, WaitOptions.defaults(), clock);
    }

    public static DurableBroker open(Path directory, DurableOptions options, GroupOptions groups, ConsumerOptions consumers,
                                     WaitOptions waits, Clock clock) throws IOException {
        DurableBroker broker = start(directory, options, groups, consumers, waits, clock, System::nanoTime, new Persistence(), event -> { });
        try { broker.initialized.get(30, TimeUnit.SECONDS); return broker; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); broker.requestClose(); throw new IOException("durable initialization interrupted; cleanup pending", e);
        } catch (ExecutionException | TimeoutException e) {
            broker.requestClose(); throw new IOException("durable initialization failed or timed out; no volatile fallback", e);
        }
    }

    static DurableBroker start(Path directory, DurableOptions options, Clock clock, LongSupplier nanos,
                               Persistence disk, Consumer<String> observer) {
        return start(directory, options, GroupOptions.defaults(), clock, nanos, disk, observer);
    }

    static DurableBroker start(Path directory, DurableOptions options, GroupOptions groups, Clock clock, LongSupplier nanos,
                               Persistence disk, Consumer<String> observer) {
        return start(directory, options, groups, ConsumerOptions.defaults(), clock, nanos, disk, observer);
    }

    static DurableBroker start(Path directory, DurableOptions options, GroupOptions groups, ConsumerOptions consumers,
                               Clock clock, LongSupplier nanos, Persistence disk, Consumer<String> observer) {
        return start(directory, options, groups, consumers, WaitOptions.defaults(), clock, nanos, disk, observer);
    }

    static DurableBroker start(Path directory, DurableOptions options, GroupOptions groups, ConsumerOptions consumers,
                               WaitOptions waits, Clock clock, LongSupplier nanos, Persistence disk, Consumer<String> observer) {
        return new DurableBroker(directory, options, groups, consumers, waits, clock, nanos, disk, observer);
    }

    private DurableBroker(Path directory, DurableOptions options, GroupOptions groups, ConsumerOptions consumerOptions, WaitOptions waitOptions, Clock clock, LongSupplier nanos,
                          Persistence disk, Consumer<String> observer) {
        this.consumerOptions = Objects.requireNonNull(consumerOptions); this.disk = disk;
        this.waitOptions = Objects.requireNonNull(waitOptions);
        int consumerCalls = consumerOptions.maxReads() + consumerOptions.maxProgressCalls() + waitOptions.maxRequests();
        consumers = new ArrayBlockingQueue<>(consumerCalls); readCompletions = new ArrayBlockingQueue<>(consumerOptions.maxReads());
        readers = new ThreadPoolExecutor(consumerOptions.readWorkers(), consumerOptions.readWorkers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(consumerOptions.maxReads()), r -> thread("kaflow-reader", r));
        consumerResponses = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(consumerCalls), r -> thread("kaflow-consumer-response", r));
        this.options = Objects.requireNonNull(options); this.nanos = Objects.requireNonNull(nanos);
        this.observer = Objects.requireNonNull(observer);
        groupOptions = Objects.requireNonNull(groups); membership = new GroupMembership(groups);
        controls = new ArrayBlockingQueue<>(groups.maxControls());
        controlResponses = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(groups.maxControls()), r -> thread("kaflow-control-response", r));
        requests = new ArrayBlockingQueue<>(options.maxCalls() + groups.maxPendingJoins() + consumerOptions.maxProgressCalls());
        responses = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(options.maxCalls()), r -> thread("kaflow-response", r));
        started = nanos.getAsLong();
        thread("kaflow-coordinator", this::coordinate).start();
        thread("kaflow-writer", () -> write(directory, clock, disk)).start();
    }

    private static Thread thread(String name, Runnable run) {
        Thread thread = new Thread(run, name); thread.setDaemon(true); return thread;
    }

    /** Internal administration, never wired to legacy REST. */
    public CompletableFuture<Void> createTopic(TopicId topic, int count) {
        Objects.requireNonNull(topic);
        try {
            LogFormat.topicBytes(new TopicPartition(topic, 0));
            if (count < 1 || count > options.maxPartitionsPerTopic()) return rejected(INVALID_ARGUMENT);
        } catch (StorageException e) { return rejected(INVALID_ARGUMENT); }
        return submit(Kind.CREATE, topic, count, null, null, 0, 0, 0);
    }

    public CompletableFuture<DurableReceipt> append(AppendCommand command) {
        Objects.requireNonNull(command);
        long bytes = (long) command.payload().length() + command.key().map(ImmutableBytes::length).orElse(0);
        if (command.payload().length() > options.storage().maxPayloadBytes()
                || command.key().map(ImmutableBytes::length).orElse(0) > options.storage().maxKeyBytes()) return rejected(INVALID_ARGUMENT);
        try { LogFormat.topicBytes(new TopicPartition(command.topic(), 0)); }
        catch (StorageException e) { return rejected(INVALID_ARGUMENT); }
        return submit(Kind.APPEND, command.topic(), 0, command, null, 0, 0, bytes);
    }

    /** Bounded fixture seam, no public Fetch or consumer state. */
    CompletableFuture<List<StoredRecord>> inspect(TopicPartition id, long start, int count, long bytes) {
        if (start < 0 || count < 1 || count > options.storage().maxReadRecords()
                || bytes < 0 || bytes > options.storage().maxReadBytes()) return rejected(INVALID_ARGUMENT);
        return submit(Kind.INSPECT, id.topic(), count, null, id, start, bytes, bytes);
    }

    private <T> CompletableFuture<T> submit(Kind kind, TopicId topic, int count, AppendCommand command,
                                           TopicPartition partition, long start, long readBytes, long charge) {
        synchronized (gate) {
            if (state != State.READY || closeRequested) return rejected(UNAVAILABLE);
            if (admittedCalls == options.maxCalls() || charge > options.maxBytes() - admittedBytes
                    || sequence == Long.MAX_VALUE) return rejected(RESOURCE_EXHAUSTED);
            Reservation reservation = new Reservation(charge);
            Work<T> work = new Work<>(++sequence, kind, topic, count, command, partition, start, readBytes, reservation);
            admittedCalls++; admittedBytes += charge;
            if (!requests.offer(work)) throw new IllegalStateException("admission queue invariant");
            signal(); return work.future;
        }
    }

    private static <T> CompletableFuture<T> rejected(DurableException.Code code) {
        return CompletableFuture.failedFuture(new DurableException(code));
    }

    public Diagnostics diagnostics() {
        synchronized (gate) {
            return new Diagnostics(state, state == State.READY && !closeRequested, admittedCalls, admittedBytes,
                    requests.size(), activeId, watchdogExpired || readWatchdogExpired, publishedView);
        }
    }

    private void transition(State next) {
        synchronized (gate) { state = next; }
        observer.accept("state-" + next);
    }

    private void signal() { synchronized (wake) { if (wake.availablePermits() == 0) wake.release(); } }
    void pulse() { signal(); }
    void awaitReady(Duration timeout) throws Exception { initialized.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }

    void awaitIdle(Duration timeout) throws InterruptedException, TimeoutException {
        long start = System.nanoTime(), budget = timeout.toNanos();
        synchronized (gate) {
            while (admittedCalls != 0 || admittedControls != 0 || admittedReads != 0 || admittedProgress != 0 || admittedWaits != 0) {
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) throw new TimeoutException("broker work still admitted");
                TimeUnit.NANOSECONDS.timedWait(gate, remaining);
            }
        }
    }

    private void coordinate() {
        while (state != State.CLOSED) {
            Completion done = completion.getAndSet(null);
            if (done != null) complete(done);
            if (state == State.CLOSED) break;
            if (closeRequested && state != State.CLOSING) { transition(State.CLOSING); membership.invalidate(); }
            membership.expireBatch(nanos.getAsLong());
            for (int i = 0; i < 8; i++) { ReadDone read = readCompletions.poll(); if (read == null) break; finishRead(read); }
            for (int i = 0; i < 8; i++) { var call = consumers.poll(); if (call == null) break; applyConsumer(call); }
            for (var call : reading.values()) if (!call.responseScheduled && nanos.getAsLong() - call.started >= options.watchdog().toNanos()) {
                readWatchdogExpired = true;
                if (state != State.CLOSING) transition(State.DEGRADED);
                respondConsumer(call, null, new DurableException(UNAVAILABLE), false);
            }
            activeReads = reading.size(); barriers = membership.barriers();
            for (int i = 0; i < 8; i++) {
                Control<?> control = controls.poll();
                if (control == null) break;
                applyControl(control);
            }
            groupCount = membership.groupCount(); memberCount = membership.memberCount();
            if ((initializing || active != null && active.kind != Kind.CLOSE) && !watchdogExpired
                    && nanos.getAsLong() - started >= options.watchdog().toNanos()) {
                watchdogExpired = true;
                if (state != State.CLOSING) transition(State.DEGRADED);
                if (initializing) initialized.completeExceptionally(new DurableException(UNAVAILABLE));
                if (active != null) respond(active, null, new DurableException(UNAVAILABLE), false);
            }
            serviceWaiters();
            if (state == State.DEGRADED || state == State.CLOSING) rejectQueued();
            if (!initializing && active == null) {
                if (state == State.CLOSING && !closeDispatched && controls.isEmpty() && consumers.isEmpty() && reading.isEmpty() && waiters.size() == 0) {
                    closeDispatched = true;
                    dispatch(new Work<>(-1, Kind.CLOSE, null, 0, null, null, 0, 0, null));
                } else if (state == State.READY) {
                    Work<?> work = requests.poll();
                    if (work != null) {
                        synchronized (gate) {
                            DurableException invalid = closeRequested ? new DurableException(UNAVAILABLE) : prepare(work);
                            if (invalid != null) respond(work, null, invalid, true);
                            else if (work.noWrite) respond(work, work.start, null, true);
                            else dispatch(work);
                        }
                    }
                }
            }
            try { wake.tryAcquire(10, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { closeRequested = true; }
        }
    }

    private DurableException prepare(Work<?> work) {
        if (work.consumer != null) {
            var call = work.consumer;
            try {
                validateConsumer(call);
                long previous = progress.get(call.group).get(call.partition.partition());
                if (call.offset == previous || !call.reset && call.offset < previous) { work.noWrite = true; work.start = previous; }
                else { membership.pin(call.group); work.pinned = true; }
                return null;
            } catch (DurableException e) { return e; }
        }
        TopicState topic = topics.get(work.topic);
        if (work.kind == Kind.GROUP) {
            if (topic == null) return new DurableException(NOT_FOUND);
            try { membership.checkJoin(work.control.join, nanos.getAsLong()); }
            catch (DurableException e) { return e; }
            return null;
        }
        if (work.kind == Kind.CREATE) {
            if (topic != null) return new DurableException(ALREADY_EXISTS);
            if (topics.size() == options.maxTopics() || published.size() + work.count > options.maxPartitions()) return new DurableException(RESOURCE_EXHAUSTED);
        } else {
            if (topic == null) return new DurableException(NOT_FOUND);
            if (work.kind == Kind.APPEND) {
                AppendCommand command = work.command;
                int partition;
                if (command.partition().isPresent()) partition = command.partition().getAsInt();
                else if (command.key().isPresent()) partition = keyed(command.key().orElseThrow(), topic.count);
                else { partition = topic.roundRobin; work.unkeyed = true; }
                if (partition < 0 || partition >= topic.count) return new DurableException(INVALID_ARGUMENT);
                work.partition = new TopicPartition(work.topic, partition);
            } else if (work.partition.partition() >= topic.count) return new DurableException(INVALID_ARGUMENT);
        }
        return null;
    }

    private static int keyed(ImmutableBytes key, int count) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); hash.update(key.asReadOnlyBuffer());
            return (int) Long.remainderUnsigned(ByteBuffer.wrap(hash.digest()).getLong(), count);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void dispatch(Work<?> work) {
        active = work; activeId = work.id; started = nanos.getAsLong();
        if (!writerQueue.offer(work)) throw new IllegalStateException("writer queue invariant");
    }

    private void complete(Completion done) {
        if (done.epoch != epoch || (initializing ? done.id != 0 : active == null || active.id != done.id)) {
            // A stale/misrouted completion cannot resolve a live operation or release its reservation.
            if (state != State.CLOSING) transition(State.DEGRADED);
            if (initializing) initialized.completeExceptionally(new DurableException(INTERNAL));
            if (active != null && active.kind != Kind.CLOSE) respond(active, null, new DurableException(UNAVAILABLE), false);
            return;
        }
        if (initializing) {
            initializing = false;
            if (done.error != null) {
                initialized.completeExceptionally(done.error);
                transition(State.CLOSED); responses.shutdown(); controlResponses.shutdown(); readers.shutdown(); consumerResponses.shutdown(); terminated.completeExceptionally(done.error); return;
            }
            var recovered = (DurableStore.Recovery) done.value;
            for (var t : recovered.topics()) topics.put(t.id(), new TopicState(t.count(), t.roundRobin()));
            published.putAll(recovered.ends()); snapshots.putAll(recovered.snapshots()); progress.putAll(recovered.progress()); publishedView = Map.copyOf(published);
            for (var g : recovered.groups()) membership.define(g.id(), g.topic(), topics.get(g.topic()).count);
            groupCount = membership.groupCount();
            if (!watchdogExpired && !closeRequested) { transition(State.READY); initialized.complete(null); }
            else initialized.completeExceptionally(new DurableException(UNAVAILABLE));
            return;
        }
        Work<?> work = active; active = null; activeId = 0;
        if (work.kind == Kind.CLOSE) {
            snapshots.clear(); progress.clear(); membership.closed(); barriers = 0;
            transition(State.CLOSED); responses.shutdown(); controlResponses.shutdown(); readers.shutdown(); consumerResponses.shutdown();
            if (done.error == null) terminated.complete(null); else terminated.completeExceptionally(done.error);
            return;
        }
        if (done.error == null) {
            snapshots.putAll(done.snapshots);
            if (work.consumer != null) {
                var call = work.consumer; var next = new ArrayList<>(progress.get(call.group));
                next.set(call.partition.partition(), (Long) done.value); progress.put(call.group, List.copyOf(next));
                membership.resolve(call.group, nanos.getAsLong());
            } else if (work.kind == Kind.GROUP) {
                var join = work.control.join;
                membership.define(join.group(), join.topic(), topics.get(join.topic()).count);
                progress.putIfAbsent(join.group(), java.util.Collections.nCopies(topics.get(join.topic()).count, 0L));
                groupCount = membership.groupCount();
            } else if (work.kind == Kind.CREATE) {
                topics.put(work.topic, new TopicState(work.count, 0));
                for (int p = 0; p < work.count; p++) published.put(new TopicPartition(work.topic, p), 0L);
            } else if (work.kind == Kind.APPEND) {
                DurableReceipt receipt = (DurableReceipt) done.value;
                if (!receipt.partition().equals(work.partition) || receipt.offset() != published.get(work.partition)) throw new IllegalStateException("publication ordering invariant");
                published.put(work.partition, receipt.offset() + 1);
                waiters.published(work.partition);
                if (work.unkeyed) { var topic = topics.get(work.topic); topic.roundRobin = (topic.roundRobin + 1) % topic.count; }
            }
            publishedView = Map.copyOf(published);
            observer.accept(work.kind == Kind.INSPECT ? "inspection-complete" : "published");
        } else if (!safeRejection(done.error)) {
            if (state != State.CLOSING) transition(State.DEGRADED);
        }
        if (done.error != null && work.pinned && safeRejection(done.error)) membership.resolve(work.consumer.group, nanos.getAsLong());
        respond(work, done.value, done.error == null ? null : mapped(done.error), true);
    }

    private static boolean safeRejection(Throwable error) {
        return error instanceof StorageException e && e.getSuppressed().length == 0 && (e.code() == StorageException.Code.INVALID_ARGUMENT
                || e.code() == StorageException.Code.LIMIT || e.code() == StorageException.Code.BUDGET_TOO_SMALL);
    }
    private static DurableException mapped(Throwable error) {
        if (error instanceof StorageException e) {
            if (e.code() == StorageException.Code.CORRUPT || e.code() == StorageException.Code.WRONG_IDENTITY || e.code() == StorageException.Code.UNSUPPORTED_VERSION) return new DurableException(DATA_LOSS, e);
            if (e.code() == StorageException.Code.INVALID_ARGUMENT) return new DurableException(INVALID_ARGUMENT, e);
            if (e.code() == StorageException.Code.LIMIT || e.code() == StorageException.Code.BUDGET_TOO_SMALL) return new DurableException(RESOURCE_EXHAUSTED, e);
        }
        return new DurableException(INTERNAL, error);
    }

    private void rejectQueued() {
        Work<?> work;
        while ((work = requests.poll()) != null) respond(work, null, new DurableException(UNAVAILABLE), true);
    }

    @SuppressWarnings("unchecked")
    private <T> void respond(Work<T> work, Object value, Throwable error, boolean resolved) {
        if (work.consumer != null) { respondConsumer(work.consumer, value, error, resolved); return; }
        if (work.kind == Kind.GROUP) {
            synchronized (gate) {
                Object result = null;
                if (resolved && error == null) {
                    if (state != State.READY || closeRequested || work.control.responseScheduled) error = new DurableException(UNAVAILABLE);
                    else {
                        try { result = positions(membership.join(work.control.join, nanos.getAsLong())); }
                        catch (DurableException e) { error = e; }
                        memberCount = membership.memberCount();
                    }
                }
                respondControl(work.control, result, error, resolved); return;
            }
        }
        if (resolved) work.reservation.resolve();
        if (work.responseScheduled) return;
        work.responseScheduled = true;
        Throwable responseError = error;
        responses.execute(() -> {
            try {
                if (responseError == null) work.future.complete((T) value); else work.future.completeExceptionally(responseError);
            } finally { work.reservation.delivered(); }
        });
    }

    private void write(Path directory, Clock clock, Persistence disk) {
        DurableStore store;
        try { store = DurableStore.open(directory, options, groupOptions, clock, disk); post(0, store.recovery(), null); }
        catch (Throwable error) { post(0, null, error); return; }
        for (;;) {
            Work<?> work;
            try { work = writerQueue.take(); }
            catch (InterruptedException ignored) { continue; } // Interrupting a waiter is not releasing ownership.
            Object value = null; Throwable error = null; Map<TopicPartition, ReadSnapshot> views = Map.of();
            try {
                switch (work.kind) {
                    case CREATE -> store.createTopic(work.topic, work.count);
                    case GROUP -> store.createGroup(work.control.join.group(), work.topic);
                    case APPEND -> value = store.append(work.partition, work.command.key(), work.command.payload(), work.unkeyed);
                    case INSPECT -> value = store.inspect(work.partition, work.start, work.count, work.readBytes);
                    case COMMIT, RESET -> value = store.commit(work.consumer.group, work.partition, work.consumer.offset, work.kind == Kind.RESET);
                    case CLOSE -> store.close();
                }
                if (work.kind == Kind.CREATE) views = store.snapshots();
                else if (work.kind == Kind.APPEND) views = Map.of(work.partition, store.snapshot(work.partition));
            } catch (Throwable failure) { error = failure; }
            post(work.id, value, error, views);
            if (work.kind == Kind.CLOSE) return;
        }
    }

    private void post(long id, Object value, Throwable error) {
        post(id, value, error, Map.of());
    }
    private void post(long id, Object value, Throwable error, Map<TopicPartition, ReadSnapshot> snapshots) {
        if (!completion.compareAndSet(null, new Completion(epoch, id, value, error, snapshots))) throw new IllegalStateException("completion queue invariant");
        signal();
    }

    public void requestClose() {
        synchronized (gate) { closeRequested = true; }
        signal();
    }

    /** False means ownership/response cleanup is still outstanding. Never interrupts storage. */
    public boolean shutdown(Duration timeout) throws InterruptedException, IOException {
        if (timeout.isNegative()) throw new IllegalArgumentException("negative shutdown timeout");
        requestClose(); long budget = timeout.toNanos(), start = System.nanoTime();
        try { terminated.get(budget, TimeUnit.NANOSECONDS); }
        catch (TimeoutException e) { return false; }
        catch (ExecutionException e) { throw new IOException("durable close failed", e.getCause()); }
        boolean dataDone = responses.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS);
        boolean controlDone = controlResponses.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS);
        boolean readDone = readers.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS);
        return consumerResponses.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS) && dataDone && controlDone && readDone;
    }

    @Override public void close() throws IOException {
        try { if (!shutdown(Duration.ofSeconds(10))) throw new IOException("durable shutdown incomplete; writer retains ownership until resolved"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("shutdown wait interrupted; ownership retained", e); }
    }

    public CompletableFuture<MembershipView> join(JoinCommand command) {
        Objects.requireNonNull(command);
        long input = command.group().byteLength() + GroupId.validate(command.topic().value(), 255, false)
                + GroupId.validate(command.memberLabel(), 128, true);
        return submitControl(ControlKind.JOIN, command, null, 0, null, input + assignmentCharge());
    }
    public CompletableFuture<MembershipView> heartbeat(SessionIdentity identity) {
        Objects.requireNonNull(identity);
        return submitControl(ControlKind.HEARTBEAT, null, identity, 0, null, identity.group().byteLength() + 72L + assignmentCharge());
    }
    public CompletableFuture<Void> leave(SessionIdentity identity) {
        Objects.requireNonNull(identity);
        return submitControl(ControlKind.LEAVE, null, identity, 0, null, identity.group().byteLength() + 72L + 64);
    }
    /** Observation at coordinator application time, never a transferable authorization for later work. */
    public CompletableFuture<Void> validateOwnership(SessionIdentity identity, long generation, TopicPartition partition) {
        Objects.requireNonNull(identity); Objects.requireNonNull(partition);
        if (generation < 1) return rejected(INVALID_ARGUMENT);
        return submitControl(ControlKind.VALIDATE, null, identity, generation, partition,
                identity.group().byteLength() + 72L + 64 + GroupId.validate(partition.topic().value(), 255, false) + 12);
    }
    private long assignmentCharge() { return 512L + options.maxPartitionsPerTopic() * (255L + 16); }

    public MembershipDiagnostics membershipDiagnostics() {
        synchronized (gate) {
            return new MembershipDiagnostics(groupCount, memberCount, memberCount, admittedControls,
                    controlBytes, pendingJoins, controls.size());
        }
    }

    private <T> CompletableFuture<T> submitControl(ControlKind kind, JoinCommand join, SessionIdentity identity,
                                                   long generation, TopicPartition partition, long bytes) {
        synchronized (gate) {
            if (closeRequested || state != State.READY && !(state == State.DEGRADED
                    && (kind == ControlKind.HEARTBEAT || kind == ControlKind.LEAVE))) return rejected(UNAVAILABLE);
            if (admittedControls == groupOptions.maxControls() || bytes > groupOptions.maxControlBytes() - controlBytes
                    || kind == ControlKind.JOIN && pendingJoins == groupOptions.maxPendingJoins()
                    || sequence == Long.MAX_VALUE) return rejected(RESOURCE_EXHAUSTED);
            Control<T> control = new Control<>(++sequence, kind, join, identity, generation, partition, bytes);
            admittedControls++; controlBytes += bytes;
            if (kind == ControlKind.JOIN) pendingJoins++;
            if (!controls.offer(control)) throw new IllegalStateException("control admission invariant");
            signal(); return control.future;
        }
    }

    private void applyControl(Control<?> control) {
        try {
            synchronized (gate) {
                if (closeRequested || state != State.READY && !(state == State.DEGRADED
                        && (control.kind == ControlKind.HEARTBEAT || control.kind == ControlKind.LEAVE))) throw new DurableException(UNAVAILABLE);
                long now = nanos.getAsLong(); Object result = null;
                switch (control.kind) {
                    case JOIN -> {
                        TopicState topic = topics.get(control.join.topic());
                        if (topic == null) throw new DurableException(NOT_FOUND);
                        membership.checkJoin(control.join, now);
                        if (membership.binding(control.join.group()) == null) {
                            Work<Void> work = new Work<>(control.id, Kind.GROUP, control.join.topic(), 0, null, null, 0, 0, null);
                            work.control = control;
                            if (!requests.offer(work)) throw new IllegalStateException("pending join invariant");
                            return;
                        }
                        result = positions(membership.join(control.join, now));
                    }
                    case HEARTBEAT -> result = positions(membership.heartbeat(control.identity, now));
                    case LEAVE -> membership.leave(control.identity, now);
                    case VALIDATE -> membership.validate(control.identity, control.generation, control.partition, now);
                }
                memberCount = membership.memberCount();
                respondControl(control, result, null, true);
            }
        } catch (DurableException error) { respondControl(control, null, error, true); }
    }

    @SuppressWarnings("unchecked")
    private <T> void respondControl(Control<T> control, Object result, Throwable error, boolean resolved) {
        synchronized (gate) {
            if (resolved) control.resolved = true;
            releaseControl(control);
            if (control.responseScheduled) return;
            control.responseScheduled = true;
        }
        controlResponses.execute(() -> {
            try {
                if (error == null) control.future.complete((T) result); else control.future.completeExceptionally(error);
            } finally {
                synchronized (gate) { control.delivered = true; releaseControl(control); }
            }
        });
    }
    private void releaseControl(Control<?> control) {
        if (control.resolved && control.delivered && !control.released) {
            control.released = true; admittedControls--; controlBytes -= control.bytes;
            if (control.kind == ControlKind.JOIN) pendingJoins--;
            gate.notifyAll(); signal();
        }
    }

    private static final class Control<T> {
        final long id, generation, bytes; final ControlKind kind; final JoinCommand join;
        final SessionIdentity identity; final TopicPartition partition;
        final CompletableFuture<T> future = new CompletableFuture<>();
        boolean resolved, delivered, released, responseScheduled;
        Control(long id, ControlKind kind, JoinCommand join, SessionIdentity identity, long generation,
                TopicPartition partition, long bytes) {
            this.id = id; this.kind = kind; this.join = join; this.identity = identity; this.generation = generation;
            this.partition = partition; this.bytes = bytes;
        }
    }

    private MembershipView positions(MembershipView view) {
        var offsets = progress.get(view.identity().group());
        return new MembershipView(view.identity(), view.generation(), view.leaseMillis(), view.assignments(),
                view.assignments().stream().map(p -> offsets.get(p.partition())).toList());
    }

    public CompletableFuture<FetchResult> fetch(FetchCommand command) { return fetch(command, null); }
    /** Delivery callback executes within the reservation, including serialization and transport handoff. */
    public CompletableFuture<FetchResult> fetch(FetchCommand command, BiConsumer<FetchResult, Throwable> delivery) {
        return fetch(command, delivery, new FetchCancellation());
    }
    /** The transport installs cancellation before calling this method, including before waiter admission. */
    public CompletableFuture<FetchResult> fetch(FetchCommand command, BiConsumer<FetchResult, Throwable> delivery,
                                                 FetchCancellation cancellation) {
        Objects.requireNonNull(command); Objects.requireNonNull(cancellation);
        if (command.maxWaitMillis() < 0 || command.maxWaitMillis() > waitOptions.maxWaitMillis()
                || command.generation() < 1 || command.startOffset() < 0 || command.maxRecords() < 1
                || command.maxRecords() > options.storage().maxReadRecords() || command.maxBytes() < 0
                || command.maxBytes() > options.storage().maxReadBytes()) return rejectedConsumer(INVALID_ARGUMENT, delivery);
        long charge = 3 * command.maxBytes() + 512L * command.maxRecords() + 1024
                + 3L * (36L + options.storage().maxKeyBytes() + options.storage().maxPayloadBytes())
                + 64L * options.storage().maxSegments();
        ConsumerCall<FetchResult> call = new ConsumerCall<>(command.session(), command.generation(), command.session().group(),
                command.partition(), command.startOffset(), command.maxRecords(), command.maxBytes(), false, true, charge, delivery);
        call.waitNanos = TimeUnit.MILLISECONDS.toNanos(command.maxWaitMillis()); call.cancellation = cancellation;
        return submitConsumer(call);
    }
    public CompletableFuture<Long> commit(CommitCommand command) {
        Objects.requireNonNull(command);
        if (command.generation() < 1 || command.nextOffset() < 0) return rejected(INVALID_ARGUMENT);
        return submitConsumer(new ConsumerCall<>(command.session(), command.generation(), command.session().group(),
                command.partition(), command.nextOffset(), 0, 0, false, false, 2048, null));
    }
    /** Internal administrative interface; deliberately absent from the network API. */
    public CompletableFuture<Long> reset(GroupId group, TopicPartition partition, long target) {
        Objects.requireNonNull(group); Objects.requireNonNull(partition);
        if (target < 0) return rejected(INVALID_ARGUMENT);
        return submitConsumer(new ConsumerCall<>(null, 0, group, partition, target, 0, 0, true, false, 2048, null));
    }
    private <T> CompletableFuture<T> rejectedConsumer(DurableException.Code code, BiConsumer<T, Throwable> delivery) {
        var error = new DurableException(code);
        if (delivery != null) delivery.accept(null, error); // No retained result; caller/transport owns rejection.
        return CompletableFuture.failedFuture(error);
    }
    private <T> CompletableFuture<T> submitConsumer(ConsumerCall<T> call) {
        // Exact identity validation occurs without retaining or allocating payload buffers.
        try { GroupId.validate(call.partition.topic().value(), 255, false); }
        catch (IllegalArgumentException e) { return rejectedConsumer(INVALID_ARGUMENT, call.delivery); }
        DurableException.Code rejection;
        synchronized (gate) {
            if (state != State.READY || closeRequested) rejection = UNAVAILABLE;
            else if (sequence == Long.MAX_VALUE || (call.waitNanos > 0 ? waitFull(call)
                    : call.read ? readFull(call)
                    : admittedProgress == consumerOptions.maxProgressCalls() || call.charge > consumerOptions.maxProgressCharge() - progressCharge))
                rejection = RESOURCE_EXHAUSTED;
            else {
                call.id = ++sequence;
                call.admittedAt = nanos.getAsLong();
                if (call.waitNanos > 0) {
                    call.waitReserved = true; admittedWaits++; waitCharge += WaitOptions.METADATA_CHARGE;
                    waitSessions.merge(call.identity, 1, Integer::sum); waitPartitions.merge(call.partition, 1, Integer::sum);
                } else if (call.read) reserveRead(call);
                else { admittedProgress++; progressCharge += call.charge; }
                call.future.whenComplete((value, error) -> signal());
                if (!consumers.offer(call)) throw new IllegalStateException("consumer admission invariant");
                signal(); return call.future;
            }
        }
        return rejectedConsumer(rejection, call.delivery);
    }
    public ConsumerDiagnostics consumerDiagnostics() {
        synchronized (gate) { return new ConsumerDiagnostics(admittedReads, readCharge, admittedProgress, progressCharge,
                activeReads, barriers, readers.getQueue().size()); }
    }
    private void validateConsumer(ConsumerCall<?> call) {
        if (state != State.READY || closeRequested) throw new DurableException(UNAVAILABLE);
        if (call.cancelled()) throw new DurableException(UNAVAILABLE);
        if (call.reset) membership.inactive(call.group, call.partition, nanos.getAsLong());
        else membership.validate(call.identity, call.generation, call.partition, nanos.getAsLong());
        if (call.barrier != null && call.barrier != membership.barrier(call.group)) throw new DurableException(UNAVAILABLE);
        Long end = published.get(call.partition);
        if (end == null) throw new DurableException(FENCED);
        if (call.offset > end) throw new DurableException(OUT_OF_RANGE);
    }
    private void applyConsumer(ConsumerCall<?> call) {
        try {
            synchronized (gate) {
                validateConsumer(call);
                if (!call.read) {
                    Work<?> work = new Work<>(call.id, call.reset ? Kind.RESET : Kind.COMMIT, call.partition.topic(),
                            0, null, call.partition, call.offset, 0, null);
                    work.consumer = call;
                    if (!requests.offer(work)) throw new IllegalStateException("progress queue invariant");
                    observer.accept("progress-queued"); return;
                }
                if (call.waitNanos > 0) {
                    call.barrier = membership.barrier(call.group);
                    observer.accept("fetch-registering");
                    if (call.offset == published.get(call.partition)) {
                        if (expired(call)) emptyWait(call);
                        else {
                            call.phase = FetchPhase.WAITING;
                            waiters.add(call.id, call.partition, call.identity, call);
                            observer.accept("fetch-parked");
                        }
                        return;
                    }
                }
                dispatchRead(call);
            }
        } catch (DurableException error) { respondConsumer(call, null, error, true); }
    }
    private boolean waitFull(ConsumerCall<?> call) {
        return admittedWaits == waitOptions.maxRequests() || WaitOptions.METADATA_CHARGE > waitOptions.maxMetadataBytes() - waitCharge
                || waitSessions.getOrDefault(call.identity, 0) >= waitOptions.maxPerSession()
                || waitPartitions.getOrDefault(call.partition, 0) >= waitOptions.maxPerPartition();
    }
    private boolean readFull(ConsumerCall<?> call) {
        return admittedReads == consumerOptions.maxReads() || call.charge > consumerOptions.maxReadCharge() - readCharge;
    }
    private void reserveRead(ConsumerCall<?> call) {
        call.readReserved = true; admittedReads++; readCharge += call.charge;
    }
    private boolean expired(ConsumerCall<?> call) { return nanos.getAsLong() - call.admittedAt >= call.waitNanos; }

    private void dispatchRead(ConsumerCall<?> call) {
        call.phase = FetchPhase.RUNNABLE;
        validateConsumer(call);
        if (!call.readReserved) {
            if (readFull(call)) throw new DurableException(RESOURCE_EXHAUSTED);
            reserveRead(call);
        }
        ReadSnapshot snapshot = snapshots.get(call.partition);
        if (snapshot.end != published.get(call.partition)) throw new IllegalStateException("read publication invariant");
        var handle = snapshot.acquire(); call.boundary = snapshot.end; call.started = nanos.getAsLong();
        call.phase = FetchPhase.READING; reading.put(call.id, call);
        readers.execute(() -> {
            Object result = null; Throwable error = null;
            try (handle) {
                disk.event("fetch-read-started");
                if (!call.cancelled()) {
                    var batch = handle.read(call.offset, call.count, call.bytes);
                    result = new FetchResult(batch.records(), batch.nextOffset(), call.boundary);
                }
                disk.event("fetch-read-complete");
            } catch (Throwable failure) { error = failure; }
            if (!readCompletions.offer(new ReadDone(call, result, error))) throw new IllegalStateException("read completion invariant");
            signal();
        });
        observer.accept("fetch-dispatched");
    }
    private void emptyWait(ConsumerCall<?> call) {
        validateConsumer(call); observer.accept("fetch-authorized");
        respondConsumer(call, new FetchResult(List.of(), call.offset, published.get(call.partition)), null, true);
    }
    private void activateWait(ConsumerCall<?> call) {
        waiters.remove(call.id);
        try { observer.accept("fetch-waking"); dispatchRead(call); }
        catch (DurableException error) { respondConsumer(call, null, error, true); }
    }
    private void serviceWaiters() {
        synchronized (gate) {
            // Removable timer slots also bound cancellation, expiry and lifecycle cleanup.
            int count = Math.min(waitOptions.batch(), waiters.size());
            for (int i = 0; i < count; i++) {
                var entry = waiters.maintenance(); if (entry == null) break;
                var call = entry.value();
                try {
                    validateConsumer(call);
                    if (expired(call)) {
                        if (call.offset < published.get(call.partition)) activateWait(call);
                        else { waiters.remove(call.id); emptyWait(call); }
                    }
                } catch (DurableException error) { waiters.remove(call.id); respondConsumer(call, null, error, true); }
            }
            for (int i = 0; i < waitOptions.batch(); i++) {
                var entry = waiters.notified(); if (entry == null) break;
                var call = entry.value();
                if (call.offset < published.get(call.partition)) activateWait(call);
                var next = waiters.first(call.partition);
                if (next != null && next.value().offset < published.get(call.partition)) waiters.published(call.partition);
            }
            waitView = new WaitDiagnostics(admittedWaits, waitCharge, waiters.size(), waiters.size(),
                    waiters.sessions(), waiters.partitions(), waiters.notifications());
            gate.notifyAll();
        }
    }
    public WaitDiagnostics waitDiagnostics() {
        synchronized (gate) {
            var view = waitView;
            return new WaitDiagnostics(admittedWaits, waitCharge, view.parked(), view.timers(),
                    view.sessions(), view.partitions(), view.notifications());
        }
    }
    /** Bounded package-private fixture observation, no public transport endpoint. */
    void awaitParked(int count, Duration timeout) throws InterruptedException, TimeoutException {
        if (count < 0 || count > waitOptions.maxRequests() || timeout.isNegative()) throw new IllegalArgumentException("wait observation bounds");
        long start = System.nanoTime(), budget = timeout.toNanos();
        synchronized (gate) {
            while (waitView.parked() != count) {
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) throw new TimeoutException("waiter observation timed out");
                TimeUnit.NANOSECONDS.timedWait(gate, remaining);
            }
        }
    }

    private void finishRead(ReadDone done) {
        var call = done.call;
        reading.remove(call.id);
        Throwable error = done.error;
        if (error != null) {
            if (!safeRejection(error) && state != State.CLOSING) transition(State.DEGRADED);
            error = mapped(error);
        }
        synchronized (gate) {
            if (error == null) {
                try { validateConsumer(call); observer.accept("fetch-authorized"); }
                catch (DurableException fenced) { error = fenced; }
            }
            respondConsumer(call, done.value, error, true);
        }
    }
    @SuppressWarnings("unchecked")
    private <T> void respondConsumer(ConsumerCall<T> call, Object value, Throwable error, boolean resolved) {
        synchronized (gate) {
            if (resolved) call.resolved = true;
            releaseConsumer(call);
            if (call.responseScheduled) return;
            call.responseScheduled = true; call.phase = FetchPhase.RESPONDING;
        }
        consumerResponses.execute(() -> {
            try {
                if (call.cancelled()) call.future.cancel(false);
                else {
                    if (call.delivery != null) call.delivery.accept((T) value, error);
                    if (error == null) call.future.complete((T) value); else call.future.completeExceptionally(error);
                }
            } catch (Throwable failure) { call.future.completeExceptionally(failure); }
            finally { synchronized (gate) { call.delivered = true; releaseConsumer(call); } }
        });
    }
    private void releaseConsumer(ConsumerCall<?> call) {
        if (call.resolved && call.delivered && !call.released) {
            call.released = true; call.phase = FetchPhase.TERMINAL;
            if (call.readReserved) { admittedReads--; readCharge -= call.charge; }
            if (!call.read) { admittedProgress--; progressCharge -= call.charge; }
            if (call.waitReserved) {
                admittedWaits--; waitCharge -= WaitOptions.METADATA_CHARGE;
                decrement(waitSessions, call.identity); decrement(waitPartitions, call.partition);
            }
            gate.notifyAll(); signal();
        }
    }
    private static <K> void decrement(Map<K, Integer> counts, K key) {
        int value = counts.get(key); if (value == 1) counts.remove(key); else counts.put(key, value - 1);
    }
    private static final class ConsumerCall<T> {
        final SessionIdentity identity; final GroupId group; final TopicPartition partition;
        final long generation, offset, bytes, charge; final int count; final boolean reset, read;
        final BiConsumer<T, Throwable> delivery;
        final CompletableFuture<T> future = new CompletableFuture<>();
        long id, boundary, started, admittedAt, waitNanos;
        boolean resolved, delivered, released, responseScheduled, readReserved, waitReserved;
        Object barrier;
        FetchPhase phase = FetchPhase.ADMITTED;
        FetchCancellation cancellation;
        boolean cancelled() { return future.isCancelled() || cancellation != null && cancellation.isCancelled(); }
        ConsumerCall(SessionIdentity identity, long generation, GroupId group, TopicPartition partition,
                     long offset, int count, long bytes, boolean reset, boolean read, long charge, BiConsumer<T, Throwable> delivery) {
            this.identity = identity; this.generation = generation; this.group = group; this.partition = partition;
            this.offset = offset; this.count = count; this.bytes = bytes; this.reset = reset; this.read = read;
            this.charge = charge; this.delivery = delivery;
        }
    }
    private record ReadDone(ConsumerCall<?> call, Object value, Throwable error) { }

    private final class Reservation {
        final long bytes; boolean resolved, delivered, released;
        Reservation(long bytes) { this.bytes = bytes; }
        void resolve() { synchronized (gate) { resolved = true; releaseIfDone(); } }
        void delivered() { synchronized (gate) { delivered = true; releaseIfDone(); } }
        private void releaseIfDone() {
            if (resolved && delivered && !released) { released = true; admittedCalls--; admittedBytes -= bytes; gate.notifyAll(); signal(); }
        }
    }
    private static final class TopicState {
        final int count; int roundRobin;
        TopicState(int count, int roundRobin) { this.count = count; this.roundRobin = roundRobin; }
    }
    private static final class Work<T> {
        final long id; final Kind kind; final TopicId topic; final int count; final AppendCommand command;
        TopicPartition partition; long start; final long readBytes; final Reservation reservation;
        final CompletableFuture<T> future = new CompletableFuture<>();
        boolean unkeyed, responseScheduled, noWrite, pinned;
        ConsumerCall<?> consumer;
        Control<?> control;
        Work(long id, Kind kind, TopicId topic, int count, AppendCommand command, TopicPartition partition,
             long start, long readBytes, Reservation reservation) {
            this.id = id; this.kind = kind; this.topic = topic; this.count = count; this.command = command;
            this.partition = partition; this.start = start; this.readBytes = readBytes; this.reservation = reservation;
        }
    }
    private record Completion(Object epoch, long id, Object value, Throwable error, Map<TopicPartition, ReadSnapshot> snapshots) { }
}
