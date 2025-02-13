package tributary.api;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tributary.core.tributaryObject.producers.*;
import tributary.core.rebalancingStrategy.RebalancingStrategy;
import tributary.core.tokenManager.TokenManager;
import tributary.core.tributaryFactory.*;
import tributary.core.tributaryObject.*;

/**
 * This class represents the main controller for managing and interacting with
 * the components of the Tributary Cluster. It provides functionality to create,
 * update, and manage topics, partitions, producers, consumers, and consumer
 * groups,
 * as well as supporting parallel operations and event consumption.
 */
public class TributaryController {
    private TributaryCluster cluster;
    private ObjectFactory objectFactory;
    private Map<String, Class<?>> typeMap;
    private TributaryHelper helper;

    /**
     * Constructor to initialize the TributaryController with default settings.
     * It sets up the cluster, object factory, and type mappings.
     */
    public TributaryController() {
        this.cluster = TributaryCluster.getInstance();
        this.objectFactory = new StringFactory();
        this.helper = new TributaryHelper();
        this.typeMap = new HashMap<>();
        typeMap.put("integer", Integer.class);
        typeMap.put("string", String.class);
        typeMap.put("bytes", byte[].class);
    }

    public void setObjectFactoryType(String type) throws IllegalArgumentException {
        Class<?> typeClass = typeMap.get(type);
        if (typeClass == null) {
            throw new IllegalArgumentException("Unsupported type: " + type + "\n");
        }
        this.objectFactory = (typeClass.equals(Integer.class)) ? new IntegerFactory() : new StringFactory();
    }

    public TributaryHelper getHelper() {
        return helper;
    }

    /*
     * All creation methods for Objects in the Tributary Cluster .
     * These methods are all streamlines to the ObjectFactory class.
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
            System.out.println("Topic " + topicId + " already exists.\n");
            return;
        }
        setObjectFactoryType(type);
        objectFactory.createTopic(topicId);
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
            System.out.println("Topic " + topicId + " does not exist.");
            return;
        } else if (topic.containsPartition(partitionId)) {
            System.out.println("Partition " + partitionId + " already exists in topic.\n");
            return;
        }
        String topicType = helper.getTopicType(topicId);
        setObjectFactoryType(topicType);
        objectFactory.createPartition(topicId, partitionId);
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
            System.out.println("Consumer group " + groupId + " already exists.\n");
            return;
        }

        String type = helper.getTopicType(topicId);
        setObjectFactoryType(type);
        objectFactory.createConsumerGroup(groupId, topicId, rebalancing);
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
            System.out.println("Consumer " + consumerId + "already exists in the group.\n");
            return;
        }
        String topicType = helper.getTopicType(group.getAssignedTopics().get(0).getId());
        setObjectFactoryType(topicType);
        objectFactory.createConsumer(groupId, consumerId);
    }

    /**
     * Creates a new producer for publishing events to a specified topic.
     *
     * @param producerId The identifier for the producer.
     * @param topicId    The topic the producer will publish to.
     * @param allocation The method of partition allocation (e.g., "random",
     *                   "manual").
     * @throws IllegalArgumentException if the producer creation fails due to type
     *                                  mismatch.
     */
    public void createProducer(String producerId, String topicId, String allocation) throws IllegalArgumentException {
        String topicType = helper.getTopic(topicId).getType().getSimpleName().toLowerCase();
        setObjectFactoryType(topicType);
        objectFactory.createProducer(producerId, topicId, allocation);
    }

    /**
     * Creates an event from a specified producer to a given topic and partition.
     *
     * @param producerId  The identifier of the producer.
     * @param topicId     The topic to publish the event to.
     * @param eventId     The event identifier (filename or other identifier).
     * @param partitionId The partition identifier (optional).
     * @throws IOException if event creation fails.
     */
    public synchronized void createEvent(String producerId, String topicId, String eventId, String partitionId)
            throws IOException {
        Producer<?> producer = helper.getProducer(producerId);
        Topic<?> topic = helper.getTopic(topicId);
        if (producer == null || topic == null) {
            System.out.println("Producer " + producerId + " or topic " + topicId + " not found.\n");
            return;
        } else if (!helper.verifyProducer(producer, topic)) {
            System.out.println("Producer does not have permission to produce to this topic.\n");
            return;
        } else if (!topic.getType().equals(producer.getType())) {
            System.out.println("Producer type does not match topic type.\n");
            return;
        }

        synchronized (topic) {
            String topicType = topic.getType().getSimpleName().toLowerCase();
            setObjectFactoryType(topicType);
            objectFactory.createEvent(producerId, topicId, eventId, partitionId);
        }
    }

    /*
     * The delete consumer method is the only delete method so far.
     * This method is to demonstrate the ability for consumer groups to automatially
     * rebalance once a consumer is deleted.
     */

    /**
     * Deletes a consumer from the Tributary Cluster and triggers a rebalance in its
     * group.
     *
     * @param consumerId The identifier of the consumer to delete.
     */
    public void deleteConsumer(String consumerId) {
        cluster.deleteConsumer(consumerId);
    }

    /*
     * Show the entirety of Topic and ConsumerGroup objects.
     * Consumer Groups will show the group, the consumers and the partitions
     * assigned to each consumer.
     * Topics will show the topic, the partitions and the respective events in order
     * of consumption in eahc partition.
     */

    /**
     * Displays assigned partitions and messages of a specified topic.
     *
     * @param topicId The identifier of the topic to display.
     */
    public void showTopic(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        if (topic != null) {
            topic.showTopic();
        } else {
            System.out.println("Topic not found: " + topicId + "\n");
        }
    }

    /**
     * Displays consumers and consumer partition assignments in a specified consumer
     * group.
     *
     * @param groupId The identifier of the consumer group to display.
     */
    public void showGroup(String groupId) {
        ConsumerGroup<?> group = cluster.getConsumerGroup(groupId);
        if (group != null) {
            group.showGroup();
        } else {
            System.out.println("Group " + groupId + " not found\n");
        }
    }

    /*
     * Consume events method. This method is used to consume events from a
     * specified partition using a specified consumer and the number of events to
     * consume.
     */

    /**
     * Consumes events from a specified partition using a consumer.
     *
     * @param consumerId     The identifier of the consumer.
     * @param partitionId    The identifier of the partition to consume from.
     * @param numberOfEvents The number of events to consume.
     */
    public void consumeEvents(String consumerId, String partitionId, int numberOfEvents) {
        Consumer<?> consumer = helper.findConsumer(consumerId);
        Partition<?> partition = helper.findPartition(partitionId);

        if (consumer == null || partition == null) {
            System.out.println("Consumer " + consumerId + " or partition " + partitionId + " not found.\n");
            return;
        }

        String topicId = partition.getAllocatedTopicId();
        Topic<?> topic = helper.getTopic(topicId);
        if (!helper.verifyConsumer(consumer, topic)) {
            System.out.println("Consumer Group of Consumer does not have permission to consume from the topic.\n");
            return;
        } else if (!consumer.listAssignedPartitions().contains(partition)) {
            System.out.println("Consumer is not assigned to the partition.\n");
            return;
        }

        if (topic.getType().equals(Integer.class)) {
            helper.consumeEventsGeneric(consumer, partition, Integer.class, numberOfEvents);
        } else if (topic.getType().equals(String.class)) {
            helper.consumeEventsGeneric(consumer, partition, String.class, numberOfEvents);
        } else {
            System.out.println("Unsupported type: " + topic.getType().getSimpleName() + "\n");
        }
    }

    /*
     * All update operations to update the state of Tributary objects.
     * Update the rebalancing strategy for a consumer group.
     * Update the Offset of a consumer to allow for message replay.
     * Update the admin token for a consumer group or producer.
     */

    /**
     * Updates the rebalancing strategy of a consumer group.
     *
     * @param groupId     The identifier of the consumer group.
     * @param rebalancing The new rebalancing strategy (e.g., "range",
     *                    "roundrobin").
     */
    public void updateRebalancing(String groupId, String rebalancing) {
        ConsumerGroup<?> group = helper.getConsumerGroup(groupId);
        RebalancingStrategy<?> prevMethod = group.getRebalanceMethod();
        group.setRebalancingMethod(rebalancing);
        group.rebalance();

        RebalancingStrategy<?> currMethod = group.getRebalanceMethod();
        if (prevMethod.equals(currMethod)) {
            System.out.println("Updated the rebalancing strategy for the "
                    + groupId + " group to " + currMethod.toString() + " rebalancing\n");
        }
    }

    /**
     * Updates the partition offset for a consumer to allow message replay or
     * backtracking.
     *
     * @param consumerId  The identifier of the consumer.
     * @param partitionId The identifier of the partition.
     * @param offset      The offset to set for the consumer.
     */
    public void updatePartitionOffset(String consumerId, String partitionId, int offset) {
        Consumer<?> consumer = helper.findConsumer(consumerId);
        Partition<?> partition = helper.findPartition(partitionId);
        Topic<?> topic = helper.getTopic(partition.getAllocatedTopicId());

        if (consumer == null || partition == null || topic == null) {
            System.out.println("Consumer, partition, or topic not found.\n");
            return;
        }
        helper.updatePartitionOffsetGeneric(consumer, partition, offset);
    }

    /**
     * Updates the admin role for a consumer group.
     *
     * @param newGroupId The identifier of the new admin consumer group.
     * @param oldGroupId The identifier of the old admin consumer group (optional).
     * @param password   The password for verification (optional).
     */
    public void updateConsumerGroupAdmin(String newGroupId, String oldGroupId, String password) {
        ConsumerGroup<?> oldGroup = helper.getConsumerGroup(oldGroupId);
        if (oldGroup == null && cluster.getAdminConsToken() != null) {
            System.out.println("Admin token exists but old Admin could not be identified.\n");
            return;
        } else if (oldGroup != null && cluster.getAdminConsToken() == null) {
            System.out.println("Old admin token not found.\n");
            return;
        } else if (oldGroup != null && cluster.getAdminConsToken() != null) {
            oldGroup.clearAssignments();
            oldGroup.setToken(null);
            oldGroup.rebalance();

            String token = cluster.getAdminConsToken();
            if (!TokenManager.validateToken(token, oldGroup.getId(), oldGroup.getCreatedTime(), password)) {
                System.out.println("Incorrect token for old Consumer Group Admin.\n");
                return;
            }
        }

        ConsumerGroup<?> newGroup = helper.getConsumerGroup(newGroupId);
        if (newGroup == null) {
            System.out.println("New Consumer Group Admin " + newGroupId + " not found.\n");
            return;
        }

        String token = TokenManager.generateToken(newGroup.getId(), newGroup.getCreatedTime());
        cluster.setAdminConsToken(token);
        newGroup.setToken(token);
        helper.assignTopicGeneric(newGroup);
        newGroup.rebalance();
        newGroup.showTopics();
        newGroup.showGroup();
    }

    /**
     * Updates the admin role for a producer.
     *
     * @param newProdId The identifier of the new admin producer.
     * @param oldProdId The identifier of the old admin producer (optional).
     * @param password  The password for verification (optional).
     */
    public void updateProducerAdmin(String newProdId, String oldProdId, String password) {
        Producer<?> oldProd = helper.getProducer(oldProdId);
        if (oldProd == null && cluster.getAdminProdToken() != null) {
            System.out.println("Admin token exists but old Admin could not be identified.\n");
            return;
        } else if (oldProd != null && cluster.getAdminProdToken() == null) {
            System.out.println("Old admin token not found.\n");
            return;
        } else if (oldProd != null && cluster.getAdminProdToken() != null) {
            oldProd.clearAssignments();
            oldProd.setToken(null);

            String token = cluster.getAdminProdToken();
            if (!TokenManager.validateToken(token, oldProd.getId(), oldProd.getCreatedTime(), password)) {
                System.out.println("Invalid token for old Producer Admin.\n");
                return;
            }
        }

        Producer<?> newProd = helper.getProducer(newProdId);
        if (newProd == null) {
            System.out.println("New Producer Admin " + newProdId + " not found.\n");
            return;
        }

        String token = TokenManager.generateToken(newProd.getId(), newProd.getCreatedTime());
        cluster.setAdminProdToken(token);
        newProd.setToken(token);
        helper.assignTopicGeneric(newProd);
        newProd.showTopics();
    }

    /*
     * All parallel operations.
     * Parallel produce events.
     * Parallel consume events in a partition.
     * Parallel operations demonstrate the system's ability to hand multiple
     * concurrent threads.
     */

    /**
     * Produces a series of events in parallel.
     *
     * @param parts An array of parameters for the producers events topics and
     *              partitions.
     */
    public void parallelProduce(String[] parts) {
        ExecutorService executorService = Executors.newCachedThreadPool();

        for (int i = 0; i < parts.length;) {
            String producerId = parts[i];
            String topicId = parts[i + 1];
            String eventFile = parts[i + 2];

            Producer<?> producer = helper.getProducer(producerId);
            Topic<?> topic = helper.getTopic(topicId);
            if (producer == null || topic == null) {
                System.out.println("Producer " + producerId + " or topic " + topicId + " not found.\n");
                break;
            } else if (!topic.getType().equals(producer.getType())) {
                System.out.println("Producer " + producerId + " type does not match Topic " + topicId + " type.\n");
                break;
            }

            String partitionId = null;
            if (producer instanceof ManualProducer) {
                if (i + 3 < parts.length) {
                    partitionId = parts[i + 3];
                    if (helper.findPartition(partitionId) == null) {
                        System.out.println("Partition " + partitionId + " not found.\n");
                        break;
                    }
                    i += 4;
                } else {
                    System.out.println("Insufficient parameters for manual producer " + producerId + "\n");
                    break;
                }
            } else {
                i += 3;
            }

            final String finalPartitionId = partitionId;
            executorService.submit(() -> {
                try {
                    createEvent(producerId, topicId, eventFile, finalPartitionId);
                } catch (IOException e) {
                    System.out.println("Error producing event: " + e.getMessage());
                }
            });
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            System.err.println("Parallel produce interrupted: " + e.getMessage());
        }
    }

    /**
     * Consume a series of events in parallel.
     *
     * @param parts An array of parameters for the consumer events and the number of
     *              events to consume.
     */
    public void parallelConsume(String[] parts) {
        ExecutorService executorService = Executors.newFixedThreadPool(parts.length / 2);

        for (int i = 0; i < parts.length;) {
            String consumerId = parts[i];
            String partitionId = parts[i + 1];

            Partition<?> partition = helper.findPartition(partitionId);
            Consumer<?> consumer = helper.findConsumer(consumerId);
            if (consumer == null || partition == null) {
                System.out.println("Consumer " + consumerId + " or partition " + partitionId + " not found.\n");
                return;
            }

            Topic<?> topic = helper.getTopic(partition.getAllocatedTopicId());
            int currentOffset = helper.parallelConsumerOffset(consumer, partition, topic.getType());
            int partitionSize = partition.listMessages().size();

            int numberOfEvents = partitionSize - currentOffset - 1;

            if (parts.length > i + 2 && helper.isInteger(parts[i + 2])) {
                numberOfEvents = Integer.parseInt(parts[i + 2]);
                i += 3;
            } else {
                i += 2;
            }

            final int finalNumberOfEvents = numberOfEvents;
            executorService.submit(() -> consumeEvents(consumerId, partitionId, finalNumberOfEvents));
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            System.err.println("Parallel consume interrupted: " + e.getMessage());
        }
    }
}
