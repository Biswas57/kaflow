package kaflow.clients;

import io.grpc.Status;
import java.util.concurrent.*;
import java.util.function.Consumer;
import kaflow.v1.*;

/** One processing caller, one independent heartbeat, one bounded membership slot. */
final class ConsumerSession implements AutoCloseable {
    record Batch(SessionIdentity session, long generation, TopicPartition partition, FetchResponse response) {
        @Override public String toString() { return "Batch[credentials redacted, records=" + response.getRecordsCount() + "]"; }
    }
    private final ClientRpc rpc;
    private final String group, topic;
    private final long deadlineMillis;
    private final Object lock = new Object();
    private final ScheduledThreadPoolExecutor heartbeats;
    private MembershipResponse membership;
    private Throwable heartbeatFailure;
    private CompletableFuture<FetchResponse> pending;
    private long epoch;
    private boolean closed, heartbeatRunning;

    ConsumerSession(ClientRpc rpc, String group, String topic, long deadlineMillis) {
        this(rpc, group, topic, deadlineMillis, true);
    }
    ConsumerSession(ClientRpc rpc, String group, String topic, long deadlineMillis, boolean schedule) {
        this.rpc = rpc; this.group = group; this.topic = topic; this.deadlineMillis = deadlineMillis;
        membership = valid(rpc.join(JoinRequest.newBuilder().setGroup(group).setTopic(topic).setMemberLabel("example").build()));
        heartbeats = new ScheduledThreadPoolExecutor(1, r -> { var t = new Thread(r, "example-heartbeat"); t.setDaemon(true); return t; });
        heartbeats.setRemoveOnCancelPolicy(true);
        if (schedule) {
            long interval = Math.max(1, membership.getLeaseDurationMillis() / 3);
            heartbeats.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
        }
    }
    private static MembershipResponse valid(MembershipResponse value) {
        int count = value.getAssignmentsCount();
        if (count > 64 || value.getCommittedNextOffsetsCount() != count || value.getRetainedStartOffsetsCount() != count
                || value.getPublishedEndOffsetsCount() != count || value.getLeaseDurationMillis() < 1
                || value.getGeneration() < 1 || !value.hasSession()) throw new IllegalStateException("invalid membership response");
        return value;
    }
    MembershipResponse view() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("consumer closed");
            if (heartbeatFailure != null) throw new MembershipUnavailable(heartbeatFailure);
            return membership;
        }
    }
    void heartbeat() { refresh(false); }
    private void refresh(boolean awaitPrior) {
        MembershipResponse before; long captured;
        synchronized (lock) {
            long began = System.nanoTime();
            while (awaitPrior && heartbeatRunning && !closed) {
                long remaining = TimeUnit.MILLISECONDS.toNanos(deadlineMillis + 1000) - (System.nanoTime() - began);
                if (remaining <= 0) throw new MembershipUnavailable(Status.DEADLINE_EXCEEDED.asRuntimeException());
                try { TimeUnit.NANOSECONDS.timedWait(lock, remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new MembershipUnavailable(e); }
            }
            if (closed || heartbeatRunning) return;
            heartbeatRunning = true; before = membership; captured = epoch;
        }
        try {
            var updated = valid(rpc.heartbeat(HeartbeatRequest.newBuilder().setSession(before.getSession()).build()));
            synchronized (lock) {
                if (!closed && epoch == captured && membership.getSession().equals(before.getSession())) {
                    if (!updated.getSession().equals(before.getSession())) throw new IllegalStateException("heartbeat changed session");
                    membership = updated; heartbeatFailure = null;
                }
            }
        } catch (RuntimeException e) {
            synchronized (lock) { if (!closed && epoch == captured) heartbeatFailure = e; }
        } finally {
            synchronized (lock) { heartbeatRunning = false; lock.notifyAll(); }
        }
    }
    /** Called by the processing loop after a failed/fenced operation; never relabels batches. */
    void recover() {
        refresh(true); // Issue a fresh observation after any earlier heartbeat and the failed operation.
        Throwable failure; long captured;
        synchronized (lock) { if (closed) throw new IllegalStateException("consumer closed"); failure = heartbeatFailure; captured = epoch; }
        if (failure == null) return;
        if (Status.fromThrowable(failure).getCode() != Status.Code.FAILED_PRECONDITION) throw new MembershipUnavailable(failure);
        var joined = valid(rpc.join(JoinRequest.newBuilder().setGroup(group).setTopic(topic).setMemberLabel("example-rejoin").build()));
        boolean accepted;
        synchronized (lock) {
            accepted = !closed && epoch == captured;
            if (accepted) { membership = joined; heartbeatFailure = null; epoch++; }
        }
        if (!accepted) {
            // A late Join created a real lease. Try to leave it, without resurrecting the client.
            rpc.leave(LeaveRequest.newBuilder().setSession(joined.getSession()).build());
            throw new IllegalStateException("membership recovery superseded or closed");
        }
    }
    Batch fetch(TopicPartition partition, long offset, int count, long bytes, long waitMillis) throws Exception {
        var captured = view();
        var request = FetchRequest.newBuilder().setSession(captured.getSession()).setGeneration(captured.getGeneration())
                .setPartition(partition).setStartOffset(offset).setMaxRecords(count).setMaxBytes(bytes).setMaxWaitMillis(waitMillis).build();
        var result = rpc.fetch(request);
        synchronized (lock) {
            if (closed || pending != null) { result.cancel(true); throw new IllegalStateException("consumer closed or concurrent Fetch"); }
            pending = result;
        }
        try { return new Batch(captured.getSession(), captured.getGeneration(), partition,
                result.get(deadlineMillis + 1000, TimeUnit.MILLISECONDS)); }
        finally { synchronized (lock) { if (pending == result) pending = null; } if (!result.isDone()) result.cancel(true); }
    }
    boolean current(Batch batch) {
        synchronized (lock) {
            return !closed && heartbeatFailure == null && membership.getSession().equals(batch.session)
                    && membership.getGeneration() == batch.generation && membership.getAssignmentsList().contains(batch.partition);
        }
    }
    /** The callback may have side effects. Reassignment during it cannot undo those effects. */
    boolean process(Batch batch, Consumer<FetchResponse> application, boolean commit) {
        if (!current(batch)) return false;
        application.accept(batch.response);
        if (commit && batch.response.getRecordsCount() != 0) {
            synchronized (lock) { if (closed) throw new IllegalStateException("closed after processing; possible duplicate processing"); }
            rpc.commit(CommitRequest.newBuilder().setSession(batch.session).setGeneration(batch.generation)
                    .setPartition(batch.partition).setNextOffset(batch.response.getNextOffset()).build());
        }
        return true;
    }
    @Override public void close() throws Exception {
        MembershipResponse last; CompletableFuture<FetchResponse> fetch;
        synchronized (lock) { if (closed) return; closed = true; epoch++; last = membership; fetch = pending; lock.notifyAll(); }
        if (fetch != null) fetch.cancel(true);
        heartbeats.shutdownNow();
        boolean stopped = heartbeats.awaitTermination(5, TimeUnit.SECONDS);
        try { rpc.leave(LeaveRequest.newBuilder().setSession(last.getSession()).build()); }
        catch (RuntimeException e) {
            if (Status.fromThrowable(e).getCode() != Status.Code.FAILED_PRECONDITION
                    && Status.fromThrowable(e).getCode() != Status.Code.UNAVAILABLE) throw e;
        }
        if (!stopped) throw new IllegalStateException("heartbeat cleanup incomplete");
    }
    static final class MembershipUnavailable extends RuntimeException {
        MembershipUnavailable(Throwable cause) { super("membership recovery required", cause); }
    }
}
