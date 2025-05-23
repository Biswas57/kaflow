package tributary.api;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

import tributary.core.tributaryObject.producers.*;
import tributary.core.rebalancingStrategy.RebalancingStrategy;
import tributary.core.util.TypeMap;
import tributary.core.tributaryFactory.*;
import tributary.core.tributaryObject.*;

/**
 * This class represents the main controller for managing and interacting with
 * the components of the Tributary Cluster. It provides functionality to create,
 * update, and manage topics, partitions, producers, consumers, and consumer
 * groups, as well as supporting parallel operations and event consumption.
 */
public class TributaryController {
    private TributaryCluster cluster;
    private TributaryHelper helper;

    /**
     * Constructor to initialize the TributaryController with default settings.
     * It sets up the cluster, object factory, and type mappings.
     */
    public TributaryController() {
        this.cluster = TributaryCluster.getInstance();
        this.helper = new TributaryHelper();
    }

    public TributaryHelper getHelper() {
        return helper;
    }

    private static Class<?> castKeyType(String key) {
        Class<?> c = TypeMap.resolve(key.toLowerCase());
        if (c == null)
            throw new IllegalArgumentException("Unsupported type: " + key);
        return c;
    }

    private static <T> ObjectFactory<T> getFactory(Class<T> t) {
        return FactoryRegistry.get(t);
    }

    /*
     * Creation methods for objects in the Tributary Cluster.
     */

    /**
     * Creates a new topic in the Tributary Cluster.
     *
     * @param topicId The identifier for the new topic.
     * @param type    The type of events that the topic will handle.
     * @throws IllegalArgumentException if the topic already exists or the type is
     *                                  unsupported.
     */
    public void createTopic(String topicId, String type) throws IllegalArgumentException {
        if (helper.getTopic(topicId) != null) {
            throw new IllegalArgumentException("Topic " + topicId + " already exists.");
        }
        getFactory(castKeyType(type)).createTopic(topicId);
    }

    /**
     * Creates a new partition in the specified topic.
     *
     * @param topicId     The identifier of the topic to add the partition to.
     * @param partitionId The identifier for the new partition.
     * @throws IllegalArgumentException if the topic does not exist or the partition
     *                                  already exists.
     */
    public void createPartition(String topicId, String partitionId) throws IllegalArgumentException {
        Topic<?> topic = helper.getTopic(topicId);
        if (topic == null) {
            throw new IllegalArgumentException("Topic " + topicId + " does not exist.");
        } else if (topic.containsPartition(partitionId)) {
            throw new IllegalArgumentException(
                    "Partition " + partitionId + " already exists in topic " + topicId + ".");
        }
        String topicType = helper.getTopicType(topicId);
        getFactory(castKeyType(topicType)).createPartition(topicId, partitionId);
    }

    /**
     * Creates a new consumer group with the given identifier, subscribed to a
     * specified topic,
     * and initializes it with a specified rebalancing strategy.
     *
     * @param groupId     The identifier for the new consumer group.
     * @param topicId     The topic the consumer group will be subscribed to.
     * @param rebalancing The rebalancing method (e.g., "range", "roundrobin").
     * @throws IllegalArgumentException if the consumer group already exists.
     */
    public void createConsumerGroup(String groupId, String topicId, String rebalancing)
            throws IllegalArgumentException {
        if (helper.getConsumerGroup(groupId) != null) {
            throw new IllegalArgumentException("Consumer group " + groupId + " already exists.");
        }
        String type = helper.getTopicType(topicId);
        getFactory(castKeyType(type)).createConsumerGroup(groupId, topicId, rebalancing);
    }

    /**
     * Creates a new consumer within a specified consumer group.
     *
     * @param groupId    The identifier of the consumer group.
     * @param consumerId The identifier for the new consumer.
     * @throws IllegalArgumentException if the consumer already exists in the group.
     */
    public void createConsumer(String groupId, String consumerId) throws IllegalArgumentException {
        ConsumerGroup<?> group = helper.getConsumerGroup(groupId);
        if (group.containsConsumer(consumerId)) {
            throw new IllegalArgumentException("Consumer " + consumerId + " already exists in group " + groupId + ".");
        }
        String topicType = helper.getTopicType(group.getAssignedTopic().getId());
        getFactory(castKeyType(topicType)).createConsumer(groupId, consumerId);
    }

    /**
     * Creates a new producer for publishing events to a specified topic.
     *
     * @param producerId The identifier for the producer.
     * @param topicId    The topic the producer will publish to.
     * @param allocation The method of partition allocation (e.g., "random",
     *                   "manual").
     * @throws IllegalArgumentException if the producer creation fails due to a type
     *                                  mismatch.
     */
    public void createProducer(String producerId, String topicId, String allocation) throws IllegalArgumentException {
        String topicType = helper.getTopic(topicId).getType().getSimpleName().toLowerCase();
        getFactory(castKeyType(topicType)).createProducer(producerId, topicId, allocation);
    }

    /*
     * Deletion methods for objects in the Tributary Cluster.
     */

    /**
     * Deletes a topic from the Tributary Cluster.
     * 
     * @param consumerId topicId The identifier of the topic to delete.
     */
    public void deleteTopic(String topicId) {
        if (helper.getTopic(topicId) == null) {
            throw new IllegalArgumentException("Topic " + topicId + " not found.");
        }
        cluster.removeTopic(topicId);
    }

    /**
     * Deletes a partition from a specified topic.
     *
     * @param topicId     The identifier of the topic.
     * @param partitionId The identifier of the partition to delete.
     */
    public void deletePartition(String topicId, String partitionId) throws IllegalArgumentException {
        Topic<?> topic = cluster.getTopic(topicId);
        if (topic == null) {
            throw new IllegalArgumentException("Topic " + topicId + " not found.");
        }
        if (!topic.containsPartition(partitionId)) {
            throw new IllegalArgumentException("Partition " + partitionId + " not found in topic " + topicId + ".");
        }
        topic.removePartition(partitionId);
    }

    /**
     * Deletes a producer from the Tributary Cluster.
     *
     * @param producerId The identifier of the producer to delete.
     */
    public void deleteProducer(String producerId) throws IllegalArgumentException {
        Producer<?> producer = cluster.getProducer(producerId);
        if (producer == null) {
            throw new IllegalArgumentException("Producer " + producerId + " not found.");
        }
        cluster.removeProducer(producerId);
    }

    /**
     * Deletes a consumer group from the Tributary Cluster.
     *
     * @param groupId The identifier of the consumer group to delete.
     */
    public void deleteConsumerGroup(String groupId) throws IllegalArgumentException {
        ConsumerGroup<?> group = cluster.getConsumerGroup(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Consumer group " + groupId + " not found.");
        }
        cluster.removeGroup(groupId);
    }

    /**
     * Deletes a consumer from a specified consumer group and triggers a rebalance
     * in
     * the group.
     *
     * @param consumerId The identifier of the consumer to delete.
     * @param groupId    The identifier of the consumer group.
     */
    public void deleteConsumer(String groupId, String consumerId) {
        ConsumerGroup<?> group = helper.getConsumerGroup(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Group " + groupId + " not found.");
        }
        if (!group.containsConsumer(consumerId)) {
            throw new IllegalArgumentException("Consumer " + consumerId + " not found in group " + groupId + ".");
        }
        group.removeConsumer(consumerId);
    }

    /*
     * Show methods for displaying information about topics, consumer groups,
     * producers, and events.
     */

    /**
     * Displays the details of a specified topic.
     * 
     * @param topicId The identifier of the topic to display.
     * @return A Topic object representing the specified topic.
     * @throws IllegalArgumentException if the topic is not found.
     */
    public Topic<?> showTopic(String topicId) throws IllegalArgumentException {
        return Optional.ofNullable(helper.getTopic(topicId))
                .orElseThrow(() -> new IllegalArgumentException("Topic" + topicId + " not found."));
    }

    /*
     * Produce Events
     */

    /**
     * Displays the details of a specified consumer group.
     * 
     * @param groupId The identifier of the consumer group to display.
     * @return A ConsumerGroup object representing the specified consumer group.
     * @throws IllegalArgumentException if the consumer group is not found.
     */
    public ConsumerGroup<?> showGroup(String groupId) throws IllegalArgumentException {
        return Optional.ofNullable(helper.getConsumerGroup(groupId))
                .orElseThrow(() -> new IllegalArgumentException("Consumer group " + groupId + " not found."));
    }

    /**
     * Produce an event from a specified producer to a given topic and partition.
     *
     * @param producerId  The identifier of the producer.
     * @param topicId     The topic to publish the event to.
     * @param eventId     The event identifier (e.g., filename).
     * @param partitionId The partition identifier (optional).
     * @throws IOException if event creation fails.
     */
    public <T> String produceMessage(String producerId, String topicId, String typeString, byte[] key, T payload,
            LocalDateTime createdAt,
            String partitionId)
            throws IllegalArgumentException {
        Producer<?> producer = helper.getProducer(producerId);
        Topic<?> topic = helper.getTopic(topicId);
        Class<?> type = TypeMap.resolve(typeString.toLowerCase());
        if (producer == null || topic == null) {
            throw new IllegalArgumentException("Producer " + producerId + " or topic " + topicId + " not found.");
        } else if (!helper.verifyProducer(producer, topic)) {
            throw new IllegalArgumentException("Producer does not have permission to produce to this topic.");
        } else if (!topic.getType().equals(producer.getType())) {
            throw new IllegalArgumentException("Producer type does not match topic type.");
        } else if (!topic.getType().equals(type)) {
            throw new IllegalArgumentException("Event type does not match topic type.");
        }

        @SuppressWarnings("unchecked")
        Producer<T> typedProducer = (Producer<T>) producer;
        @SuppressWarnings("unchecked")
        Topic<T> typedTopic = (Topic<T>) topic;
        @SuppressWarnings("unchecked")
        Class<T> eventType = (Class<T>) type;
        synchronized (topic) {
            // get messageId to return to the producer via gRPC.
            String messageId = typedProducer.produceMessage(typedTopic.listPartitions(), partitionId, eventType, key,
                    payload, createdAt);
            return messageId;
        }
    }

    /**
     * Consumes events from a specified partition using a consumer.
     *
     * @param consumerId     The identifier of the consumer.
     * @param partitionId    The identifier of the partition to consume from.
     * @param numberOfEvents The number of events to consume.
     * @return A JSONObject containing the consumed events.
     * @throws IllegalArgumentException if the consumer or partition is not found,
     *                                  or if access is denied.
     */
    public <T> T consumeEvent(String consumerId, String partitionId)
            throws IllegalArgumentException {
        // Find the consumer first
        Consumer<?> consumer = helper.findConsumer(consumerId);
        if (consumer == null) {
            throw new IllegalArgumentException("Consumer " + consumerId + " not found.");
        }

        // Retrieve the partition from the consumer's assigned partitions
        Partition<?> partition = consumer.getPartition(partitionId);
        if (partition == null) {
            throw new IllegalArgumentException(
                    "Partition " + partitionId + " not found for consumer " + consumerId + ".");
        }

        Topic<?> topic = partition.getAllocatedTopic();
        if (!helper.verifyConsumer(consumer, topic)) {
            throw new IllegalArgumentException("Consumer Group of consumer " + consumerId
                    + " does not have permission to consume from topic " + topic.getId() + ".");
        }

        return helper.consumeEventsGeneric(consumer, partition);
    }

    /**
     * Updates the rebalancing strategy of a consumer group.
     *
     * @param groupId     The identifier of the consumer group.
     * @param rebalancing The new rebalancing strategy (e.g., "range",
     *                    "roundrobin").
     */
    public void updateRebalancing(String groupId, String rebalancing) throws IllegalArgumentException {
        ConsumerGroup<?> group = helper.getConsumerGroup(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Consumer group " + groupId + " not found.");
        }
        RebalancingStrategy<?> prevMethod = group.getRebalanceMethod();
        group.setRebalancingMethod(rebalancing);
        group.rebalance();

        RebalancingStrategy<?> currMethod = group.getRebalanceMethod();
        if (prevMethod.equals(currMethod)) {
            System.out.println("Updated the rebalancing strategy for the " + groupId
                    + " group to " + currMethod.toString() + " rebalancing");
        }
    }

    /**
     * Updates the partition offset for a consumer to allow message replay or
     * backtracking.
     *
     * @param groupId     The identifier of the consumer group.
     * @param consumerId  The identifier of the consumer.
     * @param partitionId The identifier of the partition.
     * @param offset      The offset to set for the consumer.
     */
    public void updatePartitionOffset(String groupId, String consumerId, String partitionId, int offset)
            throws IllegalArgumentException {
        Consumer<?> consumer = helper.getConsumerGroup(groupId).getConsumer(consumerId);
        Partition<?> partition = helper.findPartition(partitionId);
        Topic<?> topic = partition.getAllocatedTopic();

        if (consumer == null || partition == null || topic == null) {
            throw new IllegalArgumentException("Consumer, partition, or topic not found.");
        }
        helper.updatePartitionOffsetGeneric(consumer, partition, offset);
    }
}