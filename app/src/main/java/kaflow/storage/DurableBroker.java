package kaflow.storage;

import kaflow.broker.AppendCommand;
import kaflow.groups.*;
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
    private enum Kind { CREATE, APPEND, INSPECT, GROUP, CLOSE }
    private enum ControlKind { JOIN, HEARTBEAT, LEAVE, VALIDATE }
    public record MembershipDiagnostics(int groups, int members, int timerEntries, int admittedControls,
                                        long admittedBytes, int pendingJoins, int queuedControls) { }
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
    private volatile boolean closeRequested, watchdogExpired;
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
        DurableBroker broker = start(directory, options, groups, clock, System::nanoTime, new Persistence(), event -> { });
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
        return new DurableBroker(directory, options, groups, clock, nanos, disk, observer);
    }

    private DurableBroker(Path directory, DurableOptions options, GroupOptions groups, Clock clock, LongSupplier nanos,
                          Persistence disk, Consumer<String> observer) {
        this.options = Objects.requireNonNull(options); this.nanos = Objects.requireNonNull(nanos);
        this.observer = Objects.requireNonNull(observer);
        groupOptions = Objects.requireNonNull(groups); membership = new GroupMembership(groups);
        controls = new ArrayBlockingQueue<>(groups.maxControls());
        controlResponses = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(groups.maxControls()), r -> thread("kaflow-control-response", r));
        requests = new ArrayBlockingQueue<>(options.maxCalls() + groups.maxPendingJoins());
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
                    requests.size(), activeId, watchdogExpired, publishedView);
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
            while (admittedCalls != 0 || admittedControls != 0) {
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
            if (state == State.DEGRADED || state == State.CLOSING) rejectQueued();
            if (!initializing && active == null) {
                if (state == State.CLOSING && !closeDispatched && controls.isEmpty()) {
                    closeDispatched = true;
                    dispatch(new Work<>(-1, Kind.CLOSE, null, 0, null, null, 0, 0, null));
                } else if (state == State.READY) {
                    Work<?> work = requests.poll();
                    if (work != null) {
                        DurableException invalid = prepare(work);
                        if (invalid == null) {
                            synchronized (gate) {
                                if (closeRequested) respond(work, null, new DurableException(UNAVAILABLE), true);
                                else dispatch(work);
                            }
                        } else respond(work, null, invalid, true);
                    }
                }
            }
            try { wake.tryAcquire(10, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { closeRequested = true; }
        }
    }

    private DurableException prepare(Work<?> work) {
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
                transition(State.CLOSED); responses.shutdown(); controlResponses.shutdown(); terminated.completeExceptionally(done.error); return;
            }
            var recovered = (DurableStore.Recovery) done.value;
            for (var t : recovered.topics()) topics.put(t.id(), new TopicState(t.count(), t.roundRobin()));
            published.putAll(recovered.ends()); publishedView = Map.copyOf(published);
            for (var g : recovered.groups()) membership.define(g.id(), g.topic(), topics.get(g.topic()).count);
            groupCount = membership.groupCount();
            if (!watchdogExpired && !closeRequested) { transition(State.READY); initialized.complete(null); }
            else initialized.completeExceptionally(new DurableException(UNAVAILABLE));
            return;
        }
        Work<?> work = active; active = null; activeId = 0;
        if (work.kind == Kind.CLOSE) {
            transition(State.CLOSED); responses.shutdown(); controlResponses.shutdown();
            if (done.error == null) terminated.complete(null); else terminated.completeExceptionally(done.error);
            return;
        }
        if (done.error == null) {
            if (work.kind == Kind.GROUP) {
                var join = work.control.join;
                membership.define(join.group(), join.topic(), topics.get(join.topic()).count);
                groupCount = membership.groupCount();
            } else if (work.kind == Kind.CREATE) {
                topics.put(work.topic, new TopicState(work.count, 0));
                for (int p = 0; p < work.count; p++) published.put(new TopicPartition(work.topic, p), 0L);
            } else if (work.kind == Kind.APPEND) {
                DurableReceipt receipt = (DurableReceipt) done.value;
                if (!receipt.partition().equals(work.partition) || receipt.offset() != published.get(work.partition)) throw new IllegalStateException("publication ordering invariant");
                published.put(work.partition, receipt.offset() + 1);
                if (work.unkeyed) { var topic = topics.get(work.topic); topic.roundRobin = (topic.roundRobin + 1) % topic.count; }
            }
            publishedView = Map.copyOf(published);
            observer.accept(work.kind == Kind.INSPECT ? "inspection-complete" : "published");
        } else if (!safeRejection(done.error)) {
            if (state != State.CLOSING) transition(State.DEGRADED);
        }
        respond(work, done.value, done.error == null ? null : mapped(done.error), true);
    }

    private static boolean safeRejection(Throwable error) {
        return error instanceof StorageException e && e.getSuppressed().length == 0 && (e.code() == StorageException.Code.INVALID_ARGUMENT
                || e.code() == StorageException.Code.LIMIT || e.code() == StorageException.Code.BUDGET_TOO_SMALL);
    }
    private static DurableException mapped(Throwable error) {
        if (error instanceof StorageException e) {
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
        if (work.kind == Kind.GROUP) {
            synchronized (gate) {
                Object result = null;
                if (resolved && error == null) {
                    if (state != State.READY || closeRequested || work.control.responseScheduled) error = new DurableException(UNAVAILABLE);
                    else {
                        try { result = membership.join(work.control.join, nanos.getAsLong()); }
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
            Object value = null; Throwable error = null;
            try {
                switch (work.kind) {
                    case CREATE -> store.createTopic(work.topic, work.count);
                    case GROUP -> store.createGroup(work.control.join.group(), work.topic);
                    case APPEND -> value = store.append(work.partition, work.command.key(), work.command.payload(), work.unkeyed);
                    case INSPECT -> value = store.inspect(work.partition, work.start, work.count, work.readBytes);
                    case CLOSE -> store.close();
                }
            } catch (Throwable failure) { error = failure; }
            post(work.id, value, error);
            if (work.kind == Kind.CLOSE) return;
        }
    }

    private void post(long id, Object value, Throwable error) {
        if (!completion.compareAndSet(null, new Completion(epoch, id, value, error))) throw new IllegalStateException("completion queue invariant");
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
        return controlResponses.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS) && dataDone;
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
                        result = membership.join(control.join, now);
                    }
                    case HEARTBEAT -> result = membership.heartbeat(control.identity, now);
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
        TopicPartition partition; final long start, readBytes; final Reservation reservation;
        final CompletableFuture<T> future = new CompletableFuture<>();
        boolean unkeyed, responseScheduled;
        Control<?> control;
        Work(long id, Kind kind, TopicId topic, int count, AppendCommand command, TopicPartition partition,
             long start, long readBytes, Reservation reservation) {
            this.id = id; this.kind = kind; this.topic = topic; this.count = count; this.command = command;
            this.partition = partition; this.start = start; this.readBytes = readBytes; this.reservation = reservation;
        }
    }
    private record Completion(Object epoch, long id, Object value, Throwable error) { }
}
