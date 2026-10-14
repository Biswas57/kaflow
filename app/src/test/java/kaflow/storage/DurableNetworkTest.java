package kaflow.storage;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.v1.AppendRequest;
import kaflow.v1.BrokerGrpc;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import tributary.TributaryApplication;
import tributary.stream.DurableAppendService;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class DurableNetworkTest {
    @TempDir Path directory;

    @Test void optInSpringAcknowledgementSurvivesRestartAndDefaultNamespaceStaysSeparate() throws Exception {
        AtomicInteger port = new AtomicInteger(); long timestamp;
        byte[] bytes = {0,-1,-128,-61,40};
        try (var context = spring(port)) {
            var broker = context.getBean(DurableBroker.class);
            await(broker.createTopic(TOPIC, 1));
            assertThrows(RuntimeException.class, () -> context.getBean(tributary.api.TributaryController.class).showTopic(TOPIC.value()));
            var channel = channel(port.get());
            try {
                var response = BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS).append(AppendRequest.newBuilder()
                        .setTopic(TOPIC.value()).setKey(ByteString.EMPTY).setPayload(ByteString.copyFrom(bytes)).build());
                assertEquals(0, response.getOffset()); assertEquals(TOPIC.value(), response.getPartition().getTopic()); timestamp = response.getTimestampMillis();
            } finally { close(channel); }
        }
        try (var context = spring(port)) {
            var broker = context.getBean(DurableBroker.class);
            var record = await(broker.inspect(PART, 0, 1, 100)).get(0);
            assertEquals(timestamp, record.timestampMillis()); assertArrayEquals(bytes, record.payload().toByteArray()); assertTrue(record.key().isPresent()); assertEquals(0, record.key().get().length());
            var channel = channel(port.get());
            try {
                var response = BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS).append(AppendRequest.newBuilder().setTopic(TOPIC.value()).build());
                assertEquals(1, response.getOffset());
                assertTrue(await(broker.inspect(PART, 1, 1, 0)).get(0).key().isEmpty());
            } finally { close(channel); }
        }
    }

    @Test void durableInitializationFailureAbortsSpringWithoutVolatileFallback() throws Exception {
        Files.write(directory.resolve("catalog"), new byte[]{1});
        assertThrows(RuntimeException.class, () -> spring(new AtomicInteger()));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(directory.resolve("catalog")));
    }

    @Test void realTransportValidatesPresenceLimitsAndSanitizesIoFailures() throws Exception {
        Events disk = new Events();
        try (var broker = start(directory, OPTIONS, new AtomicLong(), disk, e -> {})) {
            await(broker.createTopic(TOPIC, 1));
            Server server = server(broker, 256); var channel = channel(server.getPort());
            try {
                var stub = BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.append(AppendRequest.getDefaultInstance()));
                assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.append(AppendRequest.newBuilder().setTopic(TOPIC.value()).setPartition(-1).build()));
                assertStatus(Status.Code.NOT_FOUND, () -> stub.append(AppendRequest.newBuilder().setTopic("missing").build()));
                // The pinned Netty transport can reset an oversized stream before service dispatch.
                var oversized = assertThrows(StatusRuntimeException.class, () -> stub.append(AppendRequest.newBuilder()
                        .setTopic(TOPIC.value()).setPayload(ByteString.copyFrom(new byte[257])).build()));
                assertTrue(java.util.Set.of(Status.Code.RESOURCE_EXHAUSTED, Status.Code.CANCELLED).contains(oversized.getStatus().getCode()));
                assertEquals(0, broker.diagnostics().published().get(PART));
                // Application capacity errors have the stable RESOURCE_EXHAUSTED mapping.
                assertStatus(Status.Code.RESOURCE_EXHAUSTED, () -> stub.append(AppendRequest.newBuilder()
                        .setTopic(TOPIC.value()).setPayload(ByteString.copyFrom(new byte[100])).build()));
                disk.fail = "data-forced";
                var error = assertStatus(Status.Code.INTERNAL, () -> stub.append(AppendRequest.newBuilder().setTopic(TOPIC.value()).build()));
                assertFalse(error.getMessage().contains("/private")); assertFalse(error.getMessage().contains("disk full"));
                assertStatus(Status.Code.UNAVAILABLE, () -> stub.append(AppendRequest.newBuilder().setTopic(TOPIC.value()).build()));
            } finally { close(channel); server.shutdownNow(); assertTrue(server.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    @Test void noNetworkSuccessBeforePublicationAndClientCancellationDoesNotUnlockWriter() throws Exception {
        Events disk = new Events();
        try (var broker = start(directory, OPTIONS, new AtomicLong(), disk, e -> {})) {
            await(broker.createTopic(TOPIC, 1)); broker.awaitIdle(WAIT); disk.pause = "checkpoint-synced";
            Server server = server(broker, 2000000); var channel = channel(server.getPort());
            try {
                var response = BrokerGrpc.newFutureStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .append(AppendRequest.newBuilder().setTopic(TOPIC.value()).build());
                assertTrue(disk.entered.await(5, TimeUnit.SECONDS)); assertFalse(response.isDone());
                assertEquals(0, broker.diagnostics().published().get(PART)); response.cancel(false);
                assertEquals(1, broker.diagnostics().admittedCalls());
                assertThrows(java.io.IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
                disk.proceed.countDown(); broker.awaitIdle(WAIT);
                assertEquals(1, broker.diagnostics().published().get(PART)); assertTrue(response.isCancelled());
                assertEquals(1, BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .append(AppendRequest.newBuilder().setTopic(TOPIC.value()).build()).getOffset());
            } finally { disk.proceed.countDown(); close(channel); server.shutdownNow(); assertTrue(server.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    private ConfigurableApplicationContext spring(AtomicInteger port) {
        SpringApplication app = new SpringApplication(TributaryApplication.class); app.setRegisterShutdownHook(false);
        app.addListeners((ApplicationListener<GrpcServerStartedEvent>) event -> port.set(event.getPort()));
        return app.run("--server.port=0", "--server.address=127.0.0.1", "--grpc.server.port=0", "--grpc.server.address=127.0.0.1",
                "--grpc.server.shutdown-grace-period=5s", "--spring.main.banner-mode=off", "--kaflow.durable.enabled=true", "--kaflow.durable.directory="+directory);
    }
    private Server server(DurableBroker broker, int limit) throws Exception {
        return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).maxInboundMessageSize(limit)
                .addService(new DurableAppendService(broker, OPTIONS)).build().start();
    }
    private static ManagedChannel channel(int port) { return ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build(); }
    private static void close(ManagedChannel channel) throws Exception { assertTrue(channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)); }
    private static StatusRuntimeException assertStatus(Status.Code code, org.junit.jupiter.api.function.Executable call) {
        var error = assertThrows(StatusRuntimeException.class, call); assertEquals(code,error.getStatus().getCode()); return error;
    }
}
