package tributary.config;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kaflow.storage.DurableBroker;
import kaflow.v1.*;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import tributary.TributaryApplication;
import tributary.core.tributaryObject.TributaryCluster;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ReleaseStartupTest {
    @TempDir Path directory;
    @Test void durableCompositionProvisionsBeforeServingAndReportsActualOperations() throws Exception {
        var event = new AtomicReference<GrpcServerStartedEvent>();
        var app = new SpringApplication(TributaryApplication.class); app.setRegisterShutdownHook(false);
        app.addListeners((ApplicationListener<GrpcServerStartedEvent>) event::set);
        try (var context = app.run("--server.port=0", "--grpc.server.port=0", "--kaflow.durable.enabled=true",
                "--kaflow.durable.directory=" + directory.resolve("store"),
                "--kaflow.durable.topics[0].name=orders", "--kaflow.durable.topics[0].partitions=1",
                "--spring.main.banner-mode=off")) {
            assertTrue(context.getBeansOfType(TributaryCluster.class).isEmpty());
            for (var address : event.get().getServer().getListenSockets())
                assertTrue(((InetSocketAddress) address).getAddress().isLoopbackAddress());
            assertFalse(event.get().getServer().getServices().stream().anyMatch(s -> s.getServiceDescriptor().getName().contains("Tributary")));
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            assertEquals(200, get(http, port, "/actuator/health/liveness"));
            assertEquals(200, get(http, port, "/actuator/health/readiness"));
            assertEquals(404, get(http, port, "/api/topics/orders"));
            var channel = ManagedChannelBuilder.forAddress("127.0.0.1", event.get().getPort()).usePlaintext().build();
            try {
                var rpc = BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                var receipt = rpc.append(AppendRequest.newBuilder().setTopic("orders").setPayload(ByteString.copyFrom(new byte[]{0, -1})).build());
                assertEquals(0, receipt.getOffset());
                var meters = context.getBean(MeterRegistry.class);
                assertEquals(1, meters.get("kaflow.rpc.outcomes").tags("method", "Append", "status", "OK").counter().count());
                int count = (int) meters.getMeters().stream().filter(m -> m.getId().getName().startsWith("kaflow.")).count();
                for (int i = 0; i < 20; i++) {
                    int id = i;
                    assertThrows(StatusRuntimeException.class, () -> rpc.append(AppendRequest.newBuilder().setTopic("missing-" + id).build()));
                }
                assertEquals(count, meters.getMeters().stream().filter(m -> m.getId().getName().startsWith("kaflow.")).count());
                assertEquals(20, meters.get("kaflow.rpc.outcomes").tags("method", "Append", "status", "NOT_FOUND").counter().count());
                var member = rpc.join(JoinRequest.newBuilder().setGroup("first").setTopic("orders").build());
                var request = FetchRequest.newBuilder().setSession(member.getSession()).setGeneration(member.getGeneration())
                        .setPartition(receipt.getPartition()).setStartOffset(1).setMaxRecords(1).setMaxBytes(10).setMaxWaitMillis(10000).build();
                var waiting = BrokerGrpc.newFutureStub(channel).withDeadlineAfter(15, TimeUnit.SECONDS).fetch(request);
                long began = System.nanoTime();
                while (meters.get("kaflow.wait.parked").gauge().value() != 1 && System.nanoTime() - began < TimeUnit.SECONDS.toNanos(3)) Thread.sleep(5);
                assertEquals(1, meters.get("kaflow.wait.parked").gauge().value());
                assertFalse(waiting.isDone()); assertEquals(0, meters.get("kaflow.read.active").gauge().value());
                waiting.cancel(true);
                began = System.nanoTime();
                while (meters.get("kaflow.wait.admitted").gauge().value() != 0 && System.nanoTime() - began < TimeUnit.SECONDS.toNanos(3)) Thread.sleep(5);
                assertEquals(0, meters.get("kaflow.wait.admitted").gauge().value());
                context.getBean(DurableBroker.class).requestClose();
                assertEquals(503, get(http, port, "/actuator/health/readiness"));
                assertEquals(200, get(http, port, "/actuator/health/liveness"));
            } finally { assertTrue(channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }
    private static int get(HttpClient http, int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
