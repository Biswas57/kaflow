package tributary.stream;

import io.grpc.stub.StreamObserver;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;

import org.springframework.beans.factory.annotation.Autowired;
import net.devh.boot.grpc.server.service.GrpcService;

import tributary.api.TributaryController;
import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.Partition;

/**
 * Implementation of the TributaryStream gRPC service.
 * This service handles both producing and consuming messages via streaming
 * gRPC.
 */
@GrpcService
public class TributaryStreamImpl extends TributaryStreamGrpc.TributaryStreamImplBase {

    private static final Logger logger = Logger.getLogger(TributaryStreamImpl.class.getName());

    private final TributaryController controller;
    private final ScheduledExecutorService scheduler;

    // Track active subscriptions for consumers
    private final ConcurrentHashMap<String, ConsumerSubscription> activeSubscriptions;

    @Autowired
    public TributaryStreamImpl(TributaryController controller) {
        this.controller = controller;
        this.scheduler = Executors.newScheduledThreadPool(10);
        this.activeSubscriptions = new ConcurrentHashMap<>();
    }

    /**
     * Bi-directional streaming RPC for producing messages.
     * Clients send ProduceRequest messages and receive ProduceAck responses.
     */
    @Override
    public StreamObserver<ProduceRequest> produce(StreamObserver<ProduceAck> responseObserver) {
        return new StreamObserver<ProduceRequest>() {

            @Override
            public void onNext(ProduceRequest request) {
                try {
                    // Convert protobuf Any to the actual payload
                    Object payload = convertAnyToPayload(request.getPayload(), request.getPayloadType());

                    // Use current time since timestamp was removed from proto
                    LocalDateTime now = LocalDateTime.now();

                    // Call the controller to produce the message
                    String messageId = controller.produceMessage(
                            request.getProducerId(),
                            request.getTopicId(),
                            request.getPayloadType(),
                            request.getKey().toByteArray(),
                            payload,
                            now,
                            request.hasPartitionId() ? request.getPartitionId() : null);

                    // Send success acknowledgment
                    ProduceAck ack = ProduceAck.newBuilder()
                            .setMessageId(messageId)
                            .setPartitionId(request.hasPartitionId() ? request.getPartitionId() : "")
                            .setSuccess(true)
                            .build();

                    responseObserver.onNext(ack);

                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error producing message", e);

                    // Send error acknowledgment
                    ProduceAck errorAck = ProduceAck.newBuilder()
                            .setMessageId("")
                            .setPartitionId(request.hasPartitionId() ? request.getPartitionId() : "")
                            .setSuccess(false)
                            .setErrorMsg(e.getMessage())
                            .build();

                    responseObserver.onNext(errorAck);
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.log(Level.SEVERE, "Error in produce stream", t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
                logger.info("Produce stream completed");
            }
        };
    }

    /**
     * Server streaming RPC for consuming messages.
     * Client sends one SubscribeRequest and receives a stream of Events.
     */
    @Override
    public void subscribe(SubscribeRequest request, StreamObserver<Event> responseObserver) {
        String consumerId = request.getConsumerId();
        String groupId = request.getGroupId();
        String partitionId = request.hasPartitionId() ? request.getPartitionId() : null;

        logger.info(String.format("Starting subscription for consumer %s in group %s", consumerId, groupId));

        try {
            // Validate that consumer exists
            Consumer<?> consumer = controller.getHelper().getConsumerGroup(groupId).getConsumer(consumerId);
            if (consumer == null) {
                responseObserver.onError(
                        new IllegalArgumentException("Consumer " + consumerId + " not found in group " + groupId));
                return;
            }

            // Create subscription tracking
            ConsumerSubscription subscription = new ConsumerSubscription(
                    consumerId, groupId, partitionId, responseObserver);
            activeSubscriptions.put(consumerId, subscription);

            // Start polling for messages
            scheduleMessagePolling(subscription);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error starting subscription", e);
            responseObserver.onError(e);
        }
    }

    /**
     * Schedule periodic polling for messages for a consumer subscription.
     */
    private void scheduleMessagePolling(ConsumerSubscription subscription) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                pollAndSendMessages(subscription);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error polling messages for consumer " + subscription.consumerId, e);
                // Remove failed subscription
                activeSubscriptions.remove(subscription.consumerId);
                subscription.responseObserver.onError(e);
            }
        }, 0, 100, TimeUnit.MILLISECONDS); // Poll every 100ms
    }

    /**
     * Poll for messages and send them to the consumer.
     */
    private void pollAndSendMessages(ConsumerSubscription subscription) {
        try {
            // If specific partition is requested, consume from that partition
            if (subscription.partitionId != null) {
                consumeFromPartition(subscription, subscription.partitionId);
            } else {
                // Consume from all assigned partitions
                Consumer<?> consumer = controller.getHelper().getConsumerGroup(subscription.groupId)
                        .getConsumer(subscription.consumerId);

                for (Partition<?> partition : consumer.listAssignedPartitions()) {
                    consumeFromPartition(subscription, partition.getId());
                }
            }
        } catch (IllegalArgumentException e) {
            // No messages available - this is normal, continue polling
            // Don't log this as it's expected behavior
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unexpected error polling messages", e);
            throw e;
        }
    }

    /**
     * Consume a message from a specific partition and send it to the client.
     */
    private void consumeFromPartition(ConsumerSubscription subscription, String partitionId) {
        try {
            Object payload = controller.consumeEvent(subscription.consumerId, partitionId);

            // Get partition info for the event
            Consumer<?> consumer = controller.getHelper().getConsumerGroup(subscription.groupId)
                    .getConsumer(subscription.consumerId);
            Partition<?> partition = consumer.getPartition(partitionId);

            if (partition != null) {
                // Create Event message
                Event event = Event.newBuilder()
                        .setTopicId(partition.getAllocatedTopic().getId())
                        .setPartitionId(partitionId)
                        .setOffset(partition.getOffset(subscription.groupId) - 1) // -1 because offset was already
                                                                                  // incremented
                        .setPayload(convertPayloadToAny(payload))
                        .build();

                subscription.responseObserver.onNext(event);
                logger.fine(String.format("Sent event to consumer %s from partition %s",
                        subscription.consumerId, partitionId));
            }
        } catch (IllegalArgumentException e) {
            // No messages available or other expected errors - don't propagate
            // This is normal when there are no messages to consume
        }
    }

    /**
     * Convert protobuf Any to actual payload object based on payload type.
     */
    private Object convertAnyToPayload(Any any, String payloadType) {
        try {
            switch (payloadType.toLowerCase()) {
                case "string":
                case "text":
                    if (any.is(StringValue.class)) {
                        return any.unpack(StringValue.class).getValue();
                    } else {
                        // Try to interpret as UTF-8 string
                        return any.getValue().toStringUtf8();
                    }
                case "bytes":
                case "binary":
                    return any.getValue().toByteArray();
                default:
                    // For other types, return the raw bytes and let the controller handle it
                    return any.getValue().toStringUtf8();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error converting Any to payload", e);
            // Fallback to string representation
            return any.getValue().toStringUtf8();
        }
    }

    /**
     * Convert payload object to protobuf Any.
     */
    private Any convertPayloadToAny(Object payload) {
        if (payload instanceof String) {
            return Any.pack(StringValue.of((String) payload));
        } else if (payload instanceof byte[]) {
            return Any.newBuilder()
                    .setValue(ByteString.copyFrom((byte[]) payload))
                    .build();
        } else {
            // Convert to string as fallback
            return Any.pack(StringValue.of(payload.toString()));
        }
    }

    /**
     * Shutdown the service gracefully.
     */
    public void shutdown() {
        logger.info("Shutting down TributaryStream service");

        // Complete all active subscriptions
        for (ConsumerSubscription subscription : activeSubscriptions.values()) {
            try {
                subscription.responseObserver.onCompleted();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error completing subscription", e);
            }
        }
        activeSubscriptions.clear();

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Helper class to track consumer subscriptions.
     */
    private static class ConsumerSubscription {
        final String consumerId;
        final String groupId;
        final String partitionId; // null means consume from all assigned partitions
        final StreamObserver<Event> responseObserver;

        ConsumerSubscription(String consumerId, String groupId, String partitionId,
                StreamObserver<Event> responseObserver) {
            this.consumerId = consumerId;
            this.groupId = groupId;
            this.partitionId = partitionId;
            this.responseObserver = responseObserver;
        }
    }
}
