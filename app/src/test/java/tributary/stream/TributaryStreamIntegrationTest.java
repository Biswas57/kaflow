package tributary.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.ArrayList;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;

import tributary.api.TributaryController;

/**
 * Integration test for TributaryStream gRPC service.
 */
public class TributaryStreamIntegrationTest {

    private static final int TEST_PORT = 9091;
    private static final String TEST_HOST = "localhost";

    private TributaryStreamServer server;
    private TributaryController controller;
    private ManagedChannel channel;
    private TributaryStreamGrpc.TributaryStreamStub asyncStub;

    private String testTopicId;
    private String testProducerId;
    private String testGroupId;
    private String testConsumerId;
    private String testPartitionId;

    @BeforeEach
    public void setup() throws Exception {
        // Generate unique test IDs to avoid conflicts
        String testId = System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        testTopicId = "test-topic-" + testId;
        testProducerId = "test-producer-" + testId;
        testGroupId = "test-group-" + testId;
        testConsumerId = "test-consumer-" + testId;
        testPartitionId = "partition-1-" + testId;

        // Setup controller with test data
        controller = new TributaryController();
        setupTestData();

        // Start gRPC server
        server = new TributaryStreamServer(TEST_PORT, controller);
        server.start();

        // Setup client
        channel = ManagedChannelBuilder.forAddress(TEST_HOST, TEST_PORT)
                .usePlaintext()
                .build();
        asyncStub = TributaryStreamGrpc.newStub(channel);

        // Give server time to start
        Thread.sleep(1000);
    }

    @AfterEach
    public void teardown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.stop();
        }
    }

    private void setupTestData() {
        // Create topic and partition
        controller.createTopic(testTopicId, "string");
        controller.createPartition(testTopicId, testPartitionId);

        // Create producer
        controller.createProducer(testProducerId, testTopicId, "manual");

        // Create consumer group and consumer
        controller.createConsumerGroup(testGroupId, testTopicId, "range");
        controller.createConsumer(testGroupId, testConsumerId);
    }

    @Test
    public void testProduceMessages() throws InterruptedException {
        CountDownLatch finishLatch = new CountDownLatch(1);
        List<ProduceAck> receivedAcks = new ArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        StreamObserver<ProduceAck> responseObserver = new StreamObserver<ProduceAck>() {
            @Override
            public void onNext(ProduceAck ack) {
                receivedAcks.add(ack);
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                finishLatch.countDown();
            }
        };

        StreamObserver<ProduceRequest> requestObserver = asyncStub.produce(responseObserver);

        // Send test messages
        for (int i = 0; i < 3; i++) {
            ProduceRequest request = ProduceRequest.newBuilder()
                    .setProducerId(testProducerId)
                    .setTopicId(testTopicId)
                    .setPartitionId(testPartitionId)
                    .setPayloadType("string")
                    .setKey(ByteString.copyFromUtf8("key-" + i))
                    .setPayload(Any.pack(StringValue.of("test-message-" + i)))
                    .build();

            requestObserver.onNext(request);
        }

        requestObserver.onCompleted();

        // Wait for completion
        assertTrue(finishLatch.await(10, TimeUnit.SECONDS), "Produce operation should complete");
        assertNull(errorRef.get(), "No errors should occur during produce");
        assertEquals(3, receivedAcks.size(), "Should receive 3 acknowledgments");

        // Verify all acks are successful
        for (ProduceAck ack : receivedAcks) {
            assertTrue(ack.getSuccess(), "All produce operations should succeed");
            assertFalse(ack.getMessageId().isEmpty(), "Message ID should be present");
        }
    }

    @Test
    public void testConsumeMessages() throws InterruptedException {
        // First produce some messages
        produceTestMessages(3);

        CountDownLatch messagesLatch = new CountDownLatch(3);
        List<Event> receivedEvents = new ArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        SubscribeRequest request = SubscribeRequest.newBuilder()
                .setConsumerId(testConsumerId)
                .setGroupId(testGroupId)
                .setPartitionId(testPartitionId)
                .build();

        StreamObserver<Event> responseObserver = new StreamObserver<Event>() {
            @Override
            public void onNext(Event event) {
                receivedEvents.add(event);
                messagesLatch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                messagesLatch.countDown();
            }

            @Override
            public void onCompleted() {
                // Consumer streams don't normally complete
            }
        };

        asyncStub.subscribe(request, responseObserver);

        // Wait for messages
        assertTrue(messagesLatch.await(15, TimeUnit.SECONDS), "Should receive all messages");
        assertNull(errorRef.get(), "No errors should occur during consume");
        assertEquals(3, receivedEvents.size(), "Should receive 3 events");

        // Verify event details
        for (int i = 0; i < receivedEvents.size(); i++) {
            Event event = receivedEvents.get(i);
            assertEquals(testTopicId, event.getTopicId());
            assertEquals(testPartitionId, event.getPartitionId());
            assertEquals(i, event.getOffset());

            // Verify payload
            try {
                assertTrue(event.getPayload().is(StringValue.class));
                String payload = event.getPayload().unpack(StringValue.class).getValue();
                assertEquals("test-message-" + i, payload);
            } catch (Exception e) {
                fail("Error unpacking payload: " + e.getMessage());
            }
        }
    }

    @Test
    public void testProduceAndConsumeIntegration() throws InterruptedException {
        CountDownLatch consumeLatch = new CountDownLatch(2);
        List<Event> receivedEvents = new ArrayList<>();

        // Start consumer first
        SubscribeRequest subscribeRequest = SubscribeRequest.newBuilder()
                .setConsumerId(testConsumerId)
                .setGroupId(testGroupId)
                .setPartitionId(testPartitionId)
                .build();

        StreamObserver<Event> consumeObserver = new StreamObserver<Event>() {
            @Override
            public void onNext(Event event) {
                receivedEvents.add(event);
                consumeLatch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                // Handle error
            }

            @Override
            public void onCompleted() {
                // Not expected for consumer streams
            }
        };

        asyncStub.subscribe(subscribeRequest, consumeObserver);

        // Give consumer time to start
        Thread.sleep(1000);

        // Now produce messages
        CountDownLatch produceLatch = new CountDownLatch(1);

        StreamObserver<ProduceAck> produceObserver = new StreamObserver<ProduceAck>() {
            @Override
            public void onNext(ProduceAck ack) {
                assertTrue(ack.getSuccess());
            }

            @Override
            public void onError(Throwable t) {
                produceLatch.countDown();
            }

            @Override
            public void onCompleted() {
                produceLatch.countDown();
            }
        };

        StreamObserver<ProduceRequest> requestObserver = asyncStub.produce(produceObserver);

        // Send 2 messages
        for (int i = 0; i < 2; i++) {
            ProduceRequest request = ProduceRequest.newBuilder()
                    .setProducerId(testProducerId)
                    .setTopicId(testTopicId)
                    .setPartitionId(testPartitionId)
                    .setPayloadType("string")
                    .setKey(ByteString.copyFromUtf8("integration-key-" + i))
                    .setPayload(Any.pack(StringValue.of("integration-message-" + i)))
                    .build();

            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();

        // Wait for both production and consumption
        assertTrue(produceLatch.await(10, TimeUnit.SECONDS), "Production should complete");
        assertTrue(consumeLatch.await(15, TimeUnit.SECONDS), "Should consume all messages");

        assertEquals(2, receivedEvents.size(), "Should receive exactly 2 events");
    }

    private void produceTestMessages(int count) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        StreamObserver<ProduceAck> responseObserver = new StreamObserver<ProduceAck>() {
            @Override
            public void onNext(ProduceAck ack) {
                // Ignore individual acks
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        StreamObserver<ProduceRequest> requestObserver = asyncStub.produce(responseObserver);

        for (int i = 0; i < count; i++) {
            ProduceRequest request = ProduceRequest.newBuilder()
                    .setProducerId(testProducerId)
                    .setTopicId(testTopicId)
                    .setPartitionId(testPartitionId)
                    .setPayloadType("string")
                    .setKey(ByteString.copyFromUtf8("setup-key-" + i))
                    .setPayload(Any.pack(StringValue.of("test-message-" + i)))
                    .build();

            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Setup messages should be produced");
    }
}
