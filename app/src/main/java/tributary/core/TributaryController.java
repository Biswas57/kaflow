package tributary.core;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tributary.api.Consumer;
import tributary.api.ConsumerGroup;
import tributary.api.Message;
import tributary.api.Partition;
import tributary.api.Topic;
import tributary.api.TributaryCluster;
import tributary.api.producers.ManualProducer;
import tributary.api.producers.Producer;
import tributary.core.tributaryFactory.IntegerFactory;
import tributary.core.tributaryFactory.ObjectFactory;
import tributary.core.tributaryFactory.StringFactory;

public class TributaryController {
    private TributaryCluster cluster;
    private ObjectFactory objectFactory;
    private Map<String, Class<?>> typeMap;

    public TributaryController() {
        this.cluster = TributaryCluster.getInstance();
        this.objectFactory = new StringFactory();
        this.typeMap = new HashMap<>();
        typeMap.put("integer", Integer.class);
        typeMap.put("string", String.class);
        typeMap.put("bytes", Byte.class);
    }

    /*
     * All Helper methods involved in the the creation, deletion and manipulation of
     * Tributary objects.
     */
    public Topic<?> getTopic(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        return topic;
    }

    public ConsumerGroup<?> getConsumerGroup(String groupId) {
        ConsumerGroup<?> group = cluster.getConsumerGroup(groupId);
        if (group == null) {
            System.out.println("Consumer group " + groupId + " does not exist.\n");
        }
        return group;
    }

    public Producer<?> getProducer(String producerId) {
        Producer<?> producer = cluster.getProducer(producerId);
        if (producer == null) {
            System.out.println("Producer " + producerId + " does not exist.\n");
            return null;
        }
        return producer;
    }

    public void setObjectFactoryType(String type) throws IllegalArgumentException {
        Class<?> typeClass = typeMap.get(type);
        if (typeClass == null) {
            throw new IllegalArgumentException("Unsupported type: " + type + "\n");
        }
        this.objectFactory = (typeClass.equals(Integer.class)) ? new IntegerFactory() : new StringFactory();
    }

    public Consumer<?> findConsumer(String consumerId) {
        Consumer<?> specifiedConsumer = null;
        for (ConsumerGroup<?> group : cluster.listConsumerGroups()) {
            for (Consumer<?> consumer : group.listConsumers()) {
                if (consumer.getId().equals(consumerId)) {
                    specifiedConsumer = consumer;
                }
            }
        }
        return specifiedConsumer;
    }

    public Partition<?> findPartition(String partitionId) {
        Partition<?> specifiedPartition = null;
        for (Topic<?> topic : cluster.listTopics()) {
            for (Partition<?> partition : topic.listPartitions()) {
                if (partition.getId().equals(partitionId)) {
                    specifiedPartition = partition;
                    break;
                }
            }
            if (specifiedPartition != null)
                break;
        }
        return specifiedPartition;
    }

    private boolean isInteger(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public ObjectFactory getFactory() {
        return objectFactory;
    }

    public TributaryCluster getCluster() {
        return cluster;
    }

    /*
     * All creation methods.
     * These methods are outsourced to the ObjectFactory class.
     */
    public void createTopic(String topicId, String type) throws IllegalArgumentException {
        if (getTopic(topicId) != null) {
            System.out.println("Topic " + topicId + " already exists.\n");
            return;
        }
        setObjectFactoryType(type);
        objectFactory.createTopic(topicId);
    }

    public void createPartition(String topicId, String partitionId) throws IllegalArgumentException {
        Topic<?> topic = getTopic(topicId);
        if (topic == null) {
            System.out.println("Topic " + topicId + " does not exist.\n");
            return;
        } else if (findPartition(partitionId) != null) {
            System.out.println("Partition " + partitionId + " already exists.\n");
            return;
        }
        String topicType = topic.getType().getSimpleName().toLowerCase();
        setObjectFactoryType(topicType);
        objectFactory.createPartition(topicId, partitionId);
    }

    public void createConsumerGroup(String groupId, String topicId, String rebalancing)
            throws IllegalArgumentException {
        if (getConsumerGroup(groupId) != null) {
            System.out.println("Consumer group " + groupId + " already exists.\n");
            return;
        }

        String type = getTopic(topicId).getType().getSimpleName().toLowerCase();
        setObjectFactoryType(type);
        objectFactory.createConsumerGroup(groupId, topicId, rebalancing);
    }

    public void createConsumer(String groupId, String consumerId) throws IllegalArgumentException {
        ConsumerGroup<?> group = getConsumerGroup(groupId);
        if (group.containsConsumer(consumerId)) {
            System.out.println("Consumer " + consumerId + "already exists in the group.\n");
            return;
        }
        String topicType = group.getAssignedTopics().get(0).getType().getSimpleName().toLowerCase();
        setObjectFactoryType(topicType);
        objectFactory.createConsumer(groupId, consumerId);
    }

    public void createProducer(String producerId, String topicId, String allocation) throws IllegalArgumentException {
        Topic<?> topic = getTopic(topicId);
        setObjectFactoryType(topic.getType().getSimpleName().toLowerCase());
        objectFactory.createProducer(producerId, topicId, allocation);
    }

    public synchronized void createEvent(String producerId, String topicId, String eventId, String partitionId)
            throws IOException {
        Producer<?> producer = getProducer(producerId);
        Topic<?> topic = getTopic(topicId);
        if (producer == null || topic == null) {
            System.out.println("Producer " + producerId + " or topic " + topicId + " not found.\n");
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
     * This was to demonstrate the ability for consumer groups to automatially
     * rebalance once a consumer is deleted.
     * Simple demonstration of the Observer Pattern implemented
     * There is no added functionality when deleting other tributary objects.
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
    public void showTopic(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        if (topic != null) {
            topic.showTopic();
        } else {
            System.out.println("Topic not found: " + topicId + "\n");
        }
    }

    public void showGroup(String groupId) {
        ConsumerGroup<?> group = cluster.getConsumerGroup(groupId);
        if (group != null) {
            group.showGroup();
        } else {
            System.out.println("Group not found: " + groupId + "\n");
        }
    }

    /*
     * Consume events from a partition. The number of events to consume is specified
     * by numberOfEvents.
     * The consumer must be assigned to the partition to consume events.
     */
    public void consumeEvents(String consumerId, String partitionId, int numberOfEvents) {
        Consumer<?> consumer = findConsumer(consumerId);
        Partition<?> partition = findPartition(partitionId);
        if (consumer == null || partition == null) {
            System.out.println("Consumer " + consumerId + " or partition " + partitionId + " not found.\n");
            return;
        } else if (!consumer.listAssignedPartitions().contains(partition)) {
            System.out.println("Consumer is not assigned to the partition.\n");
            return;
        }

        String topicId = partition.getAllocatedTopicId();
        Topic<?> topic = getTopic(topicId);

        if (topic.getType().equals(Integer.class)) {
            consumeEventsGeneric(consumer, partition, Integer.class, numberOfEvents);
        } else if (topic.getType().equals(String.class)) {
            consumeEventsGeneric(consumer, partition, String.class, numberOfEvents);
        } else {
            System.out.println("Unsupported type: " + topic.getType().getSimpleName() + "\n");
        }
    }

    private <T> void consumeEventsGeneric(Consumer<?> consumer, Partition<?> partition, Class<T> type,
            int numberOfEvents) {
        @SuppressWarnings("unchecked")
        Consumer<T> typedConsumer = (Consumer<T>) consumer;
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;
        synchronized (typedPartition) {
            consumeHelper(typedConsumer, typedPartition, numberOfEvents);
        }
    }

    private <T> void consumeHelper(Consumer<T> consumer, Partition<T> partition, int numberOfEvents) {
        List<Message<T>> messages = partition.listMessages();
        int currentOffset = partition.getOffset();
        int count = 0;

        for (int i = currentOffset + 1; i < messages.size() && count < numberOfEvents; i++, count++) {
            consumer.consume(messages.get(i), partition);
        }

        if (count < numberOfEvents) {
            System.out.println("Not enough messages to consume.\nConsumed " + count + " messages.\n");
        } else {
            System.out.println("Consumed " + count + " messages.\n");
        }
    }

    /*
     * Update the rebalancing strategy for a consumer group.
     * Update the Offset of a consumer to allow for message replay.
     * Update the admin token for a consumer group or producer.
     */
    public void updateRebalancing(String groupId, String rebalancing) {
        ConsumerGroup<?> group = getConsumerGroup(groupId);
        group.setRebalancingMethod(rebalancing);
        group.rebalance();
        System.out.println("Updated the rebalancing strategy for the "
                + groupId + " group to " + rebalancing + " rebalancing\n");
    }

    public void updatePartitionOffset(String consumerId, String partitionId, int offset) {
        Consumer<?> consumer = findConsumer(consumerId);
        Partition<?> partition = findPartition(partitionId);
        Topic<?> topic = getTopic(partition.getAllocatedTopicId());

        if (consumer == null || partition == null || topic == null) {
            System.out.println("Consumer, partition, or topic not found.\n");
            return;
        }
        updatePartitionOffsetGeneric(partition, offset);
    }

    private <T> void updatePartitionOffsetGeneric(Partition<?> partition,
            int offset) {
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;
        updateTypedConsumerOffset(typedPartition, offset);
    }

    private <T> void updateTypedConsumerOffset(Partition<T> partition, int offset) {
        // uses 1 indexing here because zero indexing is used in the consume method
        if (Math.abs(offset) > partition.getOffset()) {
            System.out.println(
                    "Playback or Backtrack Offset cannot be greater than the number of messages in the partition.\n");
            return;
            // if number is 0 return the last message in the partition
        } else if (offset == 0) {
            partition.setOffset(partition.listMessages().size());
            // if number negative return the last nth message
        } else if (offset < 0) {
            partition.setOffset(partition.listMessages().size() + offset + 1);
            // if number positive return the message at nth position in partition
        } else {
            partition.setOffset(offset);
        }
    }

    public void updateConsumerGroupAdmin(String newGroupId, String oldGroupId) {
        if (oldGroupId == null && cluster.getAdminConsToken() != null) {
            System.out.println("Admin token exists but old Admin could not be identified.\n");
            return;
        } else if (oldGroupId != null && cluster.getAdminConsToken() == null) {
            System.out.println("Old admin token not found.\n");
            return;
        } else if (oldGroupId != null && cluster.getAdminConsToken() != null) {
            // removes admin permissions from oldgroup by stripping all assigned topics and
            // rebalancing partitions
            ConsumerGroup<?> oldGroup = getConsumerGroup(oldGroupId);
            oldGroup.clearAssignments();
            oldGroup.rebalance();

            // password/token authentication here.
            String token = cluster.getAdminConsToken();
            if (!TokenManager.validateToken(token, oldGroup.getId(), oldGroup.getCreatedTime())) {
                System.out.println("Invalid token for old Consumer Group Admin.\n");
                return;
            }
        }

        ConsumerGroup<?> newGroup = getConsumerGroup(newGroupId);
        if (newGroup == null) {
            System.out.println("New Consumer Group Admin " + newGroupId + " not found.\n");
            return;
        }

        String token = TokenManager.generateToken(newGroup.getId(), newGroup.getCreatedTime());
        cluster.setAdminConsToken(token);
        assignTopicGeneric(newGroup);

        // reassign all partitions across consumers in the new admin group
        newGroup.rebalance();

        // show assigned topics to show admin perms of new group Admin
        newGroup.showTopics();
    }

    public void updateProducerAdmin(String newProdId, String oldProdId) {
        if (oldProdId == null && cluster.getAdminProdToken() != null) {
            System.out.println("Admin token exists but old Admin could not be identified.\n");
            return;
        } else if (oldProdId != null && cluster.getAdminProdToken() == null) {
            System.out.println("Old admin token not found.\n");
            return;
        } else if (oldProdId != null && cluster.getAdminProdToken() != null) {
            // removes admin permissions from old prod by stripping all assigned topics
            ConsumerGroup<?> oldProd = getConsumerGroup(oldProdId);
            oldProd.clearAssignments();

            // password/token authentication here.
            String token = cluster.getAdminProdToken();
            if (!TokenManager.validateToken(token, oldProd.getId(), oldProd.getCreatedTime())) {
                System.out.println("Invalid token for old Producer Admin.\n");
                return;
            }
        }

        Producer<?> newProd = getProducer(newProdId);
        if (newProd == null) {
            System.out.println("New Consumer Group Admin " + newProdId + " not found.\n");
            return;
        }

        String token = TokenManager.generateToken(newProd.getId(), newProd.getCreatedTime());
        cluster.setAdminConsToken(token);
        assignTopicGeneric(newProd);

        // show assigned topics to show admin perms of new Prod Admin
        newProd.showTopics();
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
     * All parallel operations.
     * Parallel produce events.
     * Parallel consume events in a partition.
     * Parallel operations demonstrate the system's ability to hand multiple
     * concurrent threads.
     */

    public void parallelProduce(String[] parts) {
        ExecutorService executorService = Executors.newCachedThreadPool();

        for (int i = 0; i < parts.length;) {
            String producerId = parts[i];
            String topicId = parts[i + 1];
            String eventFile = parts[i + 2];

            Producer<?> producer = getProducer(producerId);
            Topic<?> topic = getTopic(topicId);
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
                    if (findPartition(partitionId) == null) {
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

    public void parallelConsume(String[] parts) {
        ExecutorService executorService = Executors.newFixedThreadPool(parts.length / 2);

        for (int i = 0; i < parts.length;) {
            String consumerId = parts[i];
            String partitionId = parts[i + 1];

            Partition<?> partition = findPartition(partitionId);
            Topic<?> topic = getTopic(partition.getAllocatedTopicId());
            int currentOffset = parallelConsumerOffset(partition, topic.getType());
            int partitionSize = partition.listMessages().size();

            int numberOfEvents = partitionSize - currentOffset - 1;

            if (parts.length > i + 2 && isInteger(parts[i + 2])) {
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

    private <T> int parallelConsumerOffset(Partition<?> partition, Class<T> type) {
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;
        return typedPartition.getOffset();
    }
}
