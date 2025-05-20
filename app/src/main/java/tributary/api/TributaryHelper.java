package tributary.api;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import tributary.core.tributaryObject.producers.*;
import tributary.core.tributaryObject.*;

/**
 * Helper class for performing common operations related to the creation,
 * deletion,
 * and manipulation of Tributary objects.
 */
public class TributaryHelper {
    private TributaryCluster cluster;

    public TributaryHelper() {
        this.cluster = TributaryCluster.getInstance();
    }

    /**
     * Returns the Topic with the specified ID.
     *
     * @param topicId the topic identifier
     * @return the Topic object
     */
    public Topic<?> getTopic(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        return topic;
    }

    /**
     * Returns the type of the topic as a lowercase string.
     *
     * @param topicId the topic identifier
     * @return the type of the topic (e.g. "string", "integer")
     */
    public String getTopicType(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        return topic.getType().getSimpleName().toLowerCase();
    }

    /**
     * Returns the ConsumerGroup with the specified ID.
     *
     * @param groupId the consumer group identifier
     * @return the ConsumerGroup object
     */
    public ConsumerGroup<?> getConsumerGroup(String groupId) {
        ConsumerGroup<?> group = cluster.getConsumerGroup(groupId);
        return group;
    }

    /**
     * Returns the Producer with the specified ID.
     *
     * @param producerId the producer identifier
     * @return the Producer object
     * @throws IllegalArgumentException if the producer is not found
     */
    public Producer<?> getProducer(String producerId) {
        Producer<?> producer = cluster.getProducer(producerId);
        if (producer == null) {
            throw new IllegalArgumentException("Producer " + producerId + " does not exist.");
        }
        return producer;
    }

    /**
     * Finds and returns the Consumer with the specified ID.
     *
     * @param consumerId the consumer identifier
     * @return the Consumer object or throws an exception if not found
     * @throws IllegalArgumentException if the consumer is not found
     */
    public Consumer<?> findConsumer(String consumerId) {
        for (ConsumerGroup<?> group : cluster.listConsumerGroups()) {
            for (Consumer<?> consumer : group.listConsumers()) {
                if (consumer.getId().equals(consumerId)) {
                    return consumer;
                }
            }
        }
        throw new IllegalArgumentException("Consumer " + consumerId + " not found.");
    }

    /**
     * Finds and returns the Partition with the specified ID.
     *
     * @param partitionId the partition identifier
     * @return the Partition object or throws an exception if not found
     * @throws IllegalArgumentException if the partition is not found
     */
    public Partition<?> findPartition(String partitionId) {
        for (Topic<?> topic : cluster.listTopics()) {
            for (Partition<?> partition : topic.listPartitions()) {
                if (partition.getId().equals(partitionId)) {
                    return partition;
                }
            }
        }
        throw new IllegalArgumentException("Partition " + partitionId + " not found.");
    }

    public TributaryCluster getCluster() {
        return cluster;
    }

    /*
     * Creation method helper.
     */

    /**
     * Verifies that the given producer is allowed to publish to the specified
     * topic.
     *
     * @param prod  the producer
     * @param topic the topic
     * @return true if verified, false otherwise
     */
    public boolean verifyProducer(Producer<?> prod, Topic<?> topic) {
        String adminToken = cluster.getTokenManager().getAdminProdToken();
        if (prod.listAssignedTopics().contains(topic)) {
            return true;
        } else if (adminToken != null && prod.getToken() != null && adminToken.equals(prod.getToken())) {
            return true;
        }
        return false;
    }

    /*
     * Consumer helper methods.
     */

    /**
     * Consumes events from the specified partition using a generic consumer.
     *
     * @param consumer       the consumer
     * @param partition      the partition to consume from
     * @param type           the type of payload
     * @param numberOfEvents the number of events to consume
     * @return a JSONObject representing the consumed events mapped by consumer id
     */
    public <T> JSONObject consumeEventsGeneric(Consumer<?> consumer, Partition<?> partition, Class<T> type,
            int numberOfEvents) {
        @SuppressWarnings("unchecked")
        Consumer<T> typedConsumer = (Consumer<T>) consumer;
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;
        synchronized (typedPartition) {
            return consumeHelper(typedConsumer, typedPartition, numberOfEvents);
        }
    }

    private <T> JSONObject consumeHelper(Consumer<T> consumer, Partition<T> partition, int numberOfEvents) {
        List<Message<T>> messages = partition.listMessages();
        int currentOffset = partition.getOffset(consumer);
        int count = 0;

        JSONArray eventsArray = new JSONArray();

        for (int i = currentOffset; i < messages.size() && count < numberOfEvents; i++, count++) {
            JSONObject eventJson = consumer.consume(messages.get(i), partition);
            eventsArray.put(eventJson);
        }

        if (count < numberOfEvents) {
            System.out.println("Not enough messages to consume " + numberOfEvents
                    + " messages. Consumed " + count + " messages.");
        }

        JSONObject result = new JSONObject();
        result.put(consumer.getId(), eventsArray);

        return result;
    }

    /**
     * Verifies that the given consumer is allowed to consume from the specified
     * topic.
     *
     * @param consumer the consumer
     * @param topic    the topic
     * @return true if verified, false otherwise
     */
    public boolean verifyConsumer(Consumer<?> consumer, Topic<?> topic) {
        ConsumerGroup<?> group = getConsumerGroup(consumer.getGroup());
        String adminToken = cluster.getTokenManager().getAdminConsToken();
        if (group.listAssignedTopics().contains(topic)) {
            return true;
        } else if (adminToken != null && group.getToken() != null && adminToken.equals(group.getToken())) {
            return true;
        }
        return false;
    }

    /*
     * Update helper methods.
     */

    public <T> void updatePartitionOffsetGeneric(Consumer<?> consumer, Partition<?> partition, int offset) {
        @SuppressWarnings("unchecked")
        Consumer<T> typedConsumer = (Consumer<T>) consumer;
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;
        updateTypedConsumerOffset(typedConsumer, typedPartition, offset);
    }

    private <T> void updateTypedConsumerOffset(Consumer<T> consumer, Partition<T> partition, int offset) {
        int currentOffset = partition.getOffset(consumer);
        // Enforce that the offset must be within bounds.
        if (Math.abs(offset) > currentOffset) {
            throw new IllegalArgumentException(
                    "Playback or Backtrack Offset cannot be greater than the number of messages in the partition.");
        } else if (offset == 0) {
            partition.setOffset(consumer, partition.listMessages().size());
        } else if (offset < 0) {
            partition.setOffset(consumer, partition.listMessages().size() + offset + 1);
        } else {
            partition.setOffset(consumer, offset);
        }
    }

    public <T> void assignTopicGeneric(AdminObject<T> admin) {
        for (Topic<?> topic : cluster.listTopics()) {
            if (topic.getType() == admin.getType() && !admin.getAssignedTopics().contains(topic)) {
                @SuppressWarnings("unchecked")
                Topic<T> typedTopic = (Topic<T>) topic;
                admin.assignTopic(typedTopic);
            }
        }
    }

    /*
     * Parallel helper methods.
     */

    public <T> int parallelConsumerOffset(Consumer<?> consumer, Partition<?> partition, Class<T> type) {
        @SuppressWarnings("unchecked")
        Consumer<T> typedConsumer = (Consumer<T>) consumer;
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;

        try {
            return typedPartition.getOffset(typedConsumer);
        } catch (NullPointerException e) {
            typedPartition.setOffset(typedConsumer, 0);
            return 0;
        }
    }

    public boolean isInteger(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
