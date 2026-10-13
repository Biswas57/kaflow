package tributary.stream;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import tributary.TributaryApplication;
import tributary.api.TributaryController;
import tributary.core.tributaryObject.TributaryCluster;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SpringOwnershipTest {
    @Test
    @Timeout(40)
    void restAndGrpcShareInjectedControllerWhileV1RemainsUnregistered() throws Exception {
        AtomicInteger grpcPort = new AtomicInteger();
        SpringApplication application = new SpringApplication(TributaryApplication.class);
        application.setRegisterShutdownHook(false);
        application.addListeners((ApplicationListener<GrpcServerStartedEvent>) event -> grpcPort.set(event.getPort()));
        try (ConfigurableApplicationContext context = application.run("--server.port=0", "--server.address=127.0.0.1",
                "--grpc.server.port=0", "--grpc.server.address=127.0.0.1", "--grpc.server.shutdown-grace-period=5s",
                "--spring.main.banner-mode=off")) {
            int httpPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/topics"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"orders\",\"type\":\"string\"}")).build();
            assertEquals(201, http.send(create, HttpResponse.BodyHandlers.discarding()).statusCode());
            TributaryController controller = context.getBean(TributaryController.class);
            assertSame(context.getBean(TributaryCluster.class).getTopic("orders"), controller.showTopic("orders"));
            controller.createPartition("orders", "p0");
            controller.createProducer("producer", "orders", "manual");
            assertTrue(grpcPort.get() > 0);
            ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort.get()).usePlaintext().build();
            try {
                V1NetworkBoundaryTest.assertUnimplemented(channel);
                CountDownLatch completed = new CountDownLatch(1);
                AtomicReference<Throwable> error = new AtomicReference<>();
                AtomicReference<ProduceAck> ack = new AtomicReference<>();
                StreamObserver<ProduceRequest> producer = TributaryStreamGrpc.newStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS).produce(new StreamObserver<>() {
                            @Override public void onNext(ProduceAck value) { ack.set(value); }
                            @Override public void onError(Throwable failure) { error.set(failure); completed.countDown(); }
                            @Override public void onCompleted() { completed.countDown(); }
                        });
                producer.onNext(ProduceRequest.newBuilder().setTopicId("orders").setPartitionId("p0")
                        .setProducerId("producer").setPayloadType("string")
                        .setPayload(Any.pack(StringValue.of("shared"))).build());
                producer.onCompleted();
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                assertNull(error.get());
                assertNotNull(ack.get());
                assertTrue(ack.get().getSuccess());
                assertEquals(1, controller.showTopic("orders").getPartition("p0").listMessages().size());
            } finally {
                assertTrue(channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
