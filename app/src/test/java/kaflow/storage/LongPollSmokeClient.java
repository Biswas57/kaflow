package kaflow.storage;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kaflow.broker.TopicId;
import kaflow.v1.*;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import tributary.TributaryApplication;

/** Separate fixture using production classes extracted from the built JAR; no public observation route. */
public final class LongPollSmokeClient {
    public static void main(String[] args) throws Exception {
        AtomicInteger grpc = new AtomicInteger();
        var application = new SpringApplication(TributaryApplication.class); application.setRegisterShutdownHook(false);
        application.addListeners((ApplicationListener<GrpcServerStartedEvent>) event -> grpc.set(event.getPort()));
        int http;
        long closeStarted;
        try (var context = application.run("--server.port=0", "--server.address=127.0.0.1", "--grpc.server.port=0", "--grpc.server.address=127.0.0.1",
                "--spring.main.banner-mode=off", "--grpc.server.shutdown-grace-period=5s", "--kaflow.durable.enabled=true", "--kaflow.durable.directory=" + args[1])) {
            http = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var broker = context.getBean(DurableBroker.class);
            broker.createTopic(new TopicId("long-poll"), 1).get(5, TimeUnit.SECONDS);
            var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.get()).usePlaintext().build();
            try {
                var sync = BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
                var member = sync.join(JoinRequest.newBuilder().setGroup("long-poll-group").setTopic("long-poll").build());
                var partition = TopicPartition.newBuilder().setTopic("long-poll").setPartition(0).build();
                var read = BrokerGrpc.newFutureStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS).fetch(FetchRequest.newBuilder()
                        .setSession(member.getSession()).setGeneration(member.getGeneration()).setPartition(partition)
                        .setMaxRecords(2).setMaxBytes(32).setMaxWaitMillis(5000).build());
                broker.awaitParked(1, Duration.ofSeconds(5));
                if (read.isDone() || broker.waitDiagnostics().timers() != 1 || broker.consumerDiagnostics().reads() != 0)
                    throw new AssertionError("waiter registration/read ownership");
                var bytes = ByteString.copyFrom(new byte[] {0, -1, -128, -61, 40}); var key = ByteString.copyFrom(new byte[] {0, -1});
                var receipt = sync.append(AppendRequest.newBuilder().setTopic("long-poll").setKey(key).setPayload(bytes).build());
                var result = read.get(5, TimeUnit.SECONDS);
                if (result.getRecordsCount() != 1 || result.getNextOffset() != 1 || result.getPublishedEnd() != 1) throw new AssertionError("long-poll boundary");
                var record = result.getRecords(0);
                if (!record.getPartition().equals(partition) || record.getOffset() != receipt.getOffset() || record.getOffset() != 0
                        || record.getTimestampMillis() != receipt.getTimestampMillis() || !record.hasKey() || !record.getKey().equals(key)
                        || !record.getPayload().equals(bytes)) throw new AssertionError("long-poll exact record");
                if (!sync.heartbeat(HeartbeatRequest.newBuilder().setSession(member.getSession()).build()).getCommittedNextOffsetsList().equals(List.of(0L)))
                    throw new AssertionError("long-poll advanced progress");
                broker.awaitIdle(Duration.ofSeconds(5)); broker.awaitParked(0, Duration.ofSeconds(5));
                if (broker.waitDiagnostics().admitted() != 0 || broker.waitDiagnostics().timers() != 0 || broker.consumerDiagnostics().reads() != 0)
                    throw new AssertionError("long-poll cleanup");
            } finally { if (!channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("channel shutdown"); }
            closeStarted = System.nanoTime();
        }
        double seconds = (System.nanoTime() - closeStarted) / 1_000_000_000.0;
        if (open(http) || open(grpc.get())) throw new AssertionError("long-poll listeners still open");
        Files.writeString(Path.of(args[2]), "{\"passed\":true,\"parked_before_append\":1,\"readers_before_append\":0,\"http_port\":" + http
                + ",\"grpc_port\":" + grpc.get() + ",\"record_offset\":0,\"committed_offset\":0,\"remaining_waiters\":0,\"listeners_closed\":true,\"shutdown_seconds\":" + seconds + "}\n");
        System.out.println("LONG_POLL registered=1 readers=0 exact_record=0 progress=0 cleanup=0");
    }
    private static boolean open(int port) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 500); return true; }
        catch (java.io.IOException closed) { return false; }
    }
}
