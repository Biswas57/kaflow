package kaflow.storage;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.consumer.*;
import kaflow.groups.GroupOptions;
import kaflow.v1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tributary.stream.DurableAppendService;
import static kaflow.storage.ConsumerNetworkTest.PARTITION;
import static kaflow.storage.ConsumerNetworkTest.JOIN;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class LongPollingNetworkTest {
    @TempDir Path directory;
    final AtomicLong time = new AtomicLong();
    private final class Harness implements AutoCloseable {
        final DurableBroker broker;
        final Server server;
        final ManagedChannel channel;
        final BrokerGrpc.BrokerBlockingStub sync;
        final BrokerGrpc.BrokerFutureStub async;
        Harness() throws Exception {
            broker = DurableBroker.start(directory, OPTIONS, GroupOptions.defaults(), ConsumerOptions.defaults(), WaitOptions.defaults(), CLOCK, time::get, new Persistence(), e -> {});
            broker.awaitReady(WAIT); await(broker.createTopic(TOPIC, 1));
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(new DurableAppendService(broker, OPTIONS)).build().start();
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            sync = BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            async = BrokerGrpc.newFutureStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
        }
        @Override public void close() throws Exception {
            try { assertTrue(channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)); }
            finally { try { server.shutdownNow(); assertTrue(server.awaitTermination(5, TimeUnit.SECONDS)); } finally { assertTrue(broker.shutdown(WAIT)); } }
        }
    }
    static FetchRequest request(MembershipResponse member, long wait) {
        return FetchRequest.newBuilder().setSession(member.getSession()).setGeneration(member.getGeneration())
                .setPartition(PARTITION).setMaxRecords(128).setMaxBytes(128).setMaxWaitMillis(wait).build();
    }
    @Test void actualUnaryWaitReturnsExactPublishedBytesAndDoesNotCommit() throws Exception {
        try (var h = new Harness()) {
            var member = h.sync.join(JOIN); var read = h.async.fetch(request(member, 1000)); h.broker.awaitParked(1, WAIT);
            assertFalse(read.isDone()); assertEquals(0, h.broker.consumerDiagnostics().reads());
            var bytes = ByteString.copyFrom(new byte[] {0, -1, -128, -61, 40});
            var receipt = h.sync.append(AppendRequest.newBuilder().setTopic("orders").setKey(ByteString.EMPTY).setPayload(bytes).build());
            var result = read.get(5, TimeUnit.SECONDS); assertEquals(1, result.getRecordsCount());
            var record = result.getRecords(0); assertEquals(PARTITION, record.getPartition()); assertEquals(receipt.getOffset(), record.getOffset());
            assertEquals(receipt.getTimestampMillis(), record.getTimestampMillis()); assertEquals(bytes, record.getPayload()); assertTrue(record.hasKey());
            assertEquals(1, result.getNextOffset()); assertEquals(1, result.getPublishedEnd());
            assertEquals(List.of(0L), h.sync.heartbeat(HeartbeatRequest.newBuilder().setSession(member.getSession()).build()).getCommittedNextOffsetsList());
            LongPollingTest.clean(h.broker);
        }
    }
    @Test void omittedWaitRemainsImmediateAndServerExpiryIsSuccessfulEmpty() throws Exception {
        try (var h = new Harness()) {
            var member = h.sync.join(JOIN);
            assertEquals(0, h.sync.fetch(request(member, 0).toBuilder().clearMaxWaitMillis().build()).getRecordsCount());
            var read = h.async.fetch(request(member, 100)); h.broker.awaitParked(1, WAIT); time.set(100_000_000); h.broker.pulse();
            var result = read.get(5, TimeUnit.SECONDS); assertEquals(0, result.getRecordsCount()); assertEquals(0, result.getNextOffset());
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class, () -> h.sync.fetch(request(member, -1))).getStatus().getCode());
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class, () -> h.sync.fetch(request(member, 30001))).getStatus().getCode());
            LongPollingTest.clean(h.broker);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"deadline", "cancel", "join", "leave"})
    void transportTerminationAndOwnershipLossCannotBecomeSuccessfulExpiry(String action) throws Exception {
        try (var h = new Harness()) {
            var member = h.sync.join(JOIN);
            var read = (action.equals("deadline") ? h.async.withDeadlineAfter(2, TimeUnit.SECONDS) : h.async).fetch(request(member, 10000));
            h.broker.awaitParked(1, WAIT); assertFalse(read.isDone());
            switch (action) {
                case "cancel" -> assertTrue(read.cancel(false));
                case "join" -> h.sync.join(JOIN);
                case "leave" -> h.sync.leave(LeaveRequest.newBuilder().setSession(member.getSession()).build());
            }
            if (action.equals("cancel")) assertTrue(read.isCancelled());
            else {
                var error = assertThrows(ExecutionException.class, () -> read.get(5, TimeUnit.SECONDS));
                assertEquals(action.equals("deadline") ? Status.Code.DEADLINE_EXCEEDED : Status.Code.FAILED_PRECONDITION,
                        Status.fromThrowable(error.getCause()).getCode());
            }
            LongPollingTest.clean(h.broker); assertEquals(0, time.get());
        }
    }
}
