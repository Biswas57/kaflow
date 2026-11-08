package kaflow.clients;

import io.grpc.Status;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kaflow.v1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ConsumerSessionTest {
    private static final TopicPartition PART = TopicPartition.newBuilder().setTopic("orders").setPartition(0).build();
    private static MembershipResponse member(long generation) {
        return MembershipResponse.newBuilder().setSession(SessionIdentity.newBuilder().setGroup("g").setIncarnation("incarnation").setSessionToken("never-log-this"))
                .setGeneration(generation).setLeaseDurationMillis(30000).addAssignments(PART)
                .addCommittedNextOffsets(0).addRetainedStartOffsets(0).addPublishedEndOffsets(1).build();
    }
    private static final class Fake implements ClientRpc {
        volatile MembershipResponse current = member(1);
        volatile CommitRequest commit;
        volatile CountDownLatch heartbeatEntered, heartbeatRelease;
        final AtomicInteger commits = new AtomicInteger(), leaves = new AtomicInteger();
        volatile boolean ambiguousCommit;
        volatile CompletableFuture<FetchResponse> result = CompletableFuture.completedFuture(FetchResponse.newBuilder()
                .addRecords(kaflow.v1.Record.newBuilder().setPartition(PART).setOffset(0)).setNextOffset(1).setPublishedEnd(1).build());
        @Override public MembershipResponse join(JoinRequest request) { return current; }
        @Override public MembershipResponse heartbeat(HeartbeatRequest request) {
            if (heartbeatEntered != null) {
                heartbeatEntered.countDown();
                try { assertTrue(heartbeatRelease.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
            return current;
        }
        @Override public void leave(LeaveRequest request) { leaves.incrementAndGet(); }
        @Override public CompletableFuture<FetchResponse> fetch(FetchRequest request) { return result; }
        @Override public CommitResponse commit(CommitRequest request) {
            commit = request; commits.incrementAndGet();
            if (request.getGeneration() != current.getGeneration()) throw Status.FAILED_PRECONDITION.asRuntimeException();
            if (ambiguousCommit) {
                current = current.toBuilder().setCommittedNextOffsets(0, request.getNextOffset()).build();
                throw Status.DEADLINE_EXCEEDED.asRuntimeException();
            }
            return CommitResponse.newBuilder().setDurableNextOffset(request.getNextOffset()).build();
        }
    }
    @Test void assignmentChangeBeforeProcessingDiscardsBatchWithoutSideEffectsOrCommit() throws Exception {
        var rpc = new Fake();
        try (var session = new ConsumerSession(rpc, "g", "orders", 1000, false)) {
            var batch = session.fetch(PART, 0, 1, 10, 0); rpc.current = member(2); session.heartbeat();
            var processed = new AtomicInteger();
            assertFalse(session.process(batch, value -> processed.incrementAndGet(), true));
            assertEquals(0, processed.get()); assertEquals(0, rpc.commits.get());
            assertEquals(1, batch.generation()); assertFalse(batch.toString().contains("never-log-this"));
        }
    }
    @Test void reassignmentDuringProcessingCannotUndoEffectsAndCommitKeepsOriginalIdentity() throws Exception {
        var rpc = new Fake(); var executor = Executors.newSingleThreadExecutor();
        var processing = new CountDownLatch(1); var release = new CountDownLatch(1); var effects = new AtomicInteger();
        try (var session = new ConsumerSession(rpc, "g", "orders", 1000, false)) {
            var batch = session.fetch(PART, 0, 1, 10, 0);
            var work = executor.submit(() -> session.process(batch, value -> {
                effects.incrementAndGet(); processing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }, true));
            assertTrue(processing.await(5, TimeUnit.SECONDS)); rpc.current = member(2); session.heartbeat(); release.countDown();
            var error = assertThrows(ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
            assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(error.getCause()).getCode());
            assertEquals(1, effects.get()); assertEquals(batch.session(), rpc.commit.getSession());
            assertEquals(batch.generation(), rpc.commit.getGeneration()); assertEquals(PART, rpc.commit.getPartition());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void generationChangeBetweenProcessingAndCommitNeverRelabelsOldWork() throws Exception {
        var rpc = new Fake();
        try (var session = new ConsumerSession(rpc, "g", "orders", 1000, false)) {
            var batch = session.fetch(PART, 0, 1, 10, 0);
            assertThrows(io.grpc.StatusRuntimeException.class, () -> session.process(batch, value -> {
                rpc.current = member(2); session.heartbeat();
            }, true));
            assertEquals(1, rpc.commit.getGeneration()); assertEquals(2, session.view().getGeneration());
        }
    }
    @Test void heartbeatCompletionAfterCloseCannotResurrectMembershipAndCloseIsIdempotent() throws Exception {
        var rpc = new Fake(); var session = new ConsumerSession(rpc, "g", "orders", 1000, false);
        rpc.heartbeatEntered = new CountDownLatch(1); rpc.heartbeatRelease = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var heartbeat = executor.submit(session::heartbeat); assertTrue(rpc.heartbeatEntered.await(5, TimeUnit.SECONDS));
            session.close(); rpc.current = member(2); rpc.heartbeatRelease.countDown(); heartbeat.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, session::view); session.close(); assertEquals(1, rpc.leaves.get());
        } finally { rpc.heartbeatRelease.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void ambiguousCommitRecoversReportedProgressWithoutRelabelingOrRetryingCommit() throws Exception {
        var rpc = new Fake(); rpc.ambiguousCommit = true;
        try (var session = new ConsumerSession(rpc, "g", "orders", 1000, false)) {
            var batch = session.fetch(PART, 0, 1, 10, 0); var effects = new AtomicInteger();
            var error = assertThrows(io.grpc.StatusRuntimeException.class,
                    () -> session.process(batch, value -> effects.incrementAndGet(), true));
            assertEquals(Status.Code.DEADLINE_EXCEEDED, error.getStatus().getCode());
            session.recover();
            assertEquals(1, session.view().getCommittedNextOffsets(0));
            assertEquals(1, effects.get()); assertEquals(1, rpc.commits.get());
            assertEquals(batch.session(), rpc.commit.getSession()); assertEquals(batch.generation(), rpc.commit.getGeneration());
        }
    }
    @Test void closingCancelsPendingFetchAndNeverProcessesIt() throws Exception {
        var rpc = new Fake(); rpc.result = new CompletableFuture<>();
        var session = new ConsumerSession(rpc, "g", "orders", 1000, false);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var fetch = worker.submit(() -> session.fetch(PART, 0, 1, 10, 100));
            session.close(); // Both registration orders must cancel the outstanding transport future.
            var error = assertThrows(ExecutionException.class, () -> fetch.get(3, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof CancellationException || error.getCause() instanceof IllegalStateException);
            assertEquals(0, rpc.commits.get()); assertEquals(1, rpc.leaves.get());
        } finally { session.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
