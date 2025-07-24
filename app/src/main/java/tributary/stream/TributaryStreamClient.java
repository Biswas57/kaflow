package tributary.stream;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;

/**
 * Example client for the TributaryStream gRPC service.
 * Demonstrates both producing and consuming messages via streaming gRPC.
 */
public class TributaryStreamClient {

    private static final Logger logger = Logger.getLogger(TributaryStreamClient.class.getName());

    private final ManagedChannel channel;
    private final TributaryStreamGrpc.TributaryStreamStub asyncStub;

    public TributaryStreamClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.asyncStub = TributaryStreamGrpc.newStub(channel);
    }

    /**
     * Shutdown the client channel.
     */
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Example of producing messages using streaming gRPC.
     */
    public void produceMessages() throws InterruptedException {
        logger.info("Starting message production...");

        CountDownLatch finishLatch = new CountDownLatch(1);

        StreamObserver<ProduceAck> responseObserver = new StreamObserver<ProduceAck>() {
            @Override
            public void onNext(ProduceAck ack) {
                if (ack.getSuccess()) {
                    logger.info("Successfully produced message: " + ack.getMessageId() +
                            " to partition: " + ack.getPartitionId());
                } else {
                    logger.warning("Failed to produce message: " + ack.getErrorMsg());
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.log(Level.SEVERE, "Error in produce stream", t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("Produce stream completed");
                finishLatch.countDown();
            }
        };

        StreamObserver<ProduceRequest> requestObserver = asyncStub.produce(responseObserver);

        try {
            // Send some example messages
            for (int i = 0; i < 5; i++) {
                String message = "Hello from gRPC client - message " + i;

                ProduceRequest request = ProduceRequest.newBuilder()
                        .setProducerId("grpc-producer")
                        .setTopicId("grpc-topic")
                        .setPayloadType("string")
                        .setKey(ByteString.copyFromUtf8("key-" + i))
                        .setPayload(Any.pack(StringValue.of(message)))
                        .build();

                requestObserver.onNext(request);

                // Wait a bit between messages
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error sending produce requests", e);
            requestObserver.onError(e);
            return;
        }

        // Complete the stream
        requestObserver.onCompleted();

        // Wait for completion
        if (!finishLatch.await(30, TimeUnit.SECONDS)) {
            logger.warning("Produce operation did not complete within 30 seconds");
        }
    }

    /**
     * Example of consuming messages using streaming gRPC.
     */
    public void consumeMessages() throws InterruptedException {
        logger.info("Starting message consumption...");

        CountDownLatch finishLatch = new CountDownLatch(1);

        SubscribeRequest request = SubscribeRequest.newBuilder()
                .setConsumerId("grpc-consumer")
                .setGroupId("grpc-group")
                .build();

        StreamObserver<Event> responseObserver = new StreamObserver<Event>() {
            @Override
            public void onNext(Event event) {
                try {
                    // Try to unpack as StringValue first
                    String payload = "";
                    if (event.getPayload().is(StringValue.class)) {
                        payload = event.getPayload().unpack(StringValue.class).getValue();
                    } else {
                        payload = event.getPayload().getValue().toStringUtf8();
                    }

                    logger.info(String.format("Received event - Topic: %s, Partition: %s, Offset: %d, Payload: %s",
                            event.getTopicId(), event.getPartitionId(), event.getOffset(), payload));

                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error processing received event", e);
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.log(Level.SEVERE, "Error in consume stream", t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("Consume stream completed");
                finishLatch.countDown();
            }
        };

        asyncStub.subscribe(request, responseObserver);

        // Let it run for a while to receive messages
        logger.info("Listening for messages for 60 seconds...");
        if (!finishLatch.await(60, TimeUnit.SECONDS)) {
            logger.info("Consume operation completed after 60 seconds");
        }
    }

    /**
     * Example usage of the client.
     */
    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 9090;

        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        TributaryStreamClient client = new TributaryStreamClient(host, port);

        try {
            // Example: produce some messages
            if (args.length == 0 || args[0].equals("produce")) {
                client.produceMessages();
            }

            // Example: consume messages
            if (args.length == 0 || args[0].equals("consume")) {
                client.consumeMessages();
            }

        } finally {
            client.shutdown();
        }
    }
}
