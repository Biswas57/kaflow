package kaflow.clients;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.*;
import kaflow.v1.*;

final class GrpcClient implements ClientRpc, AutoCloseable {
    private final ManagedChannel channel;
    private final long deadlineMillis;
    GrpcClient(String host, int port, long deadlineMillis) {
        if (port < 1 || port > 65535 || deadlineMillis < 1 || deadlineMillis > 120000) throw new IllegalArgumentException("port/deadline bounds");
        this.deadlineMillis = deadlineMillis;
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().disableRetry()
                .maxInboundMessageSize(4 * 1024 * 1024).build();
    }
    private BrokerGrpc.BrokerBlockingStub rpc() { return BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS); }
    AppendResponse append(AppendRequest request) { return rpc().append(request); }
    @Override public MembershipResponse join(JoinRequest request) { return rpc().join(request); }
    @Override public MembershipResponse heartbeat(HeartbeatRequest request) { return rpc().heartbeat(request); }
    @Override public void leave(LeaveRequest request) { rpc().leave(request); }
    @Override public CommitResponse commit(CommitRequest request) { return rpc().commit(request); }
    @Override public CompletableFuture<FetchResponse> fetch(FetchRequest request) {
        var wire = BrokerGrpc.newFutureStub(channel).withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS).fetch(request);
        var result = new CompletableFuture<FetchResponse>();
        result.whenComplete((v, e) -> { if (result.isCancelled()) wire.cancel(true); });
        wire.addListener(() -> {
            try { result.complete(wire.get()); }
            catch (ExecutionException e) { result.completeExceptionally(e.getCause()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); result.completeExceptionally(e); }
            catch (CancellationException e) { result.cancel(false); }
        }, Runnable::run);
        return result;
    }
    @Override public void close() throws InterruptedException {
        channel.shutdownNow();
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("client channel cleanup incomplete");
    }
}
