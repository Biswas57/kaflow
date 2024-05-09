package tributary.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tributary.api.Consumer;
import tributary.api.ConsumerGroup;
import tributary.api.Message;
import tributary.api.Partition;
import tributary.api.Topic;
import tributary.api.TributaryCluster;
import tributary.api.producers.Producer;
import tributary.core.tributaryFactory.IntegerFactory;
import tributary.core.tributaryFactory.ObjectFactory;
import tributary.core.tributaryFactory.StringFactory;

public class TributaryController {
    private TributaryCluster tributaryCluster;
    private ObjectFactory objectFactory;
    private Map<String, Class<?>> typeMap;

    public TributaryController() {
        this.tributaryCluster = TributaryCluster.getInstance();
        this.objectFactory = new StringFactory(tributaryCluster);
        this.typeMap = new HashMap<>();
        typeMap.put("integer", Integer.class);
        typeMap.put("string", String.class);
    }

    public Topic<?> getTopic(String topicId) {
        Topic<?> topic = tributaryCluster.getTopic(topicId);
        if (topic == null) {
            System.out.println("Topic with ID " + topicId + " does not exist.");
            return null;
        }
        return topic;
    }

    public ConsumerGroup<?> getConsumerGroup(String groupId) {
        ConsumerGroup<?> group = tributaryCluster.getConsumerGroup(groupId);
        if (group == null) {
            System.out.println("Consumer group with ID " + groupId + " does not exist.");
            return null;
        }
        return group;
    }

    public Producer<?> getProducer(String producerId) {
        Producer<?> producer = tributaryCluster.getProducer(producerId);
        if (producer == null) {
            System.out.println("Producer with ID " + producerId + " does not exist.");
            return null;
        }
        return producer;
    }

    public void setObjectFactoryType(Class<?> type) {
        if (type.equals(Integer.class)) {
            this.objectFactory = new IntegerFactory(tributaryCluster);
        } else if (type.equals(String.class)) {
            this.objectFactory = new StringFactory(tributaryCluster);
        } else {
            System.out.println("Unsupported type: " + type.getSimpleName());
            this.objectFactory = new StringFactory(tributaryCluster);
        }
    }

    public void createTopic(String topicId, String type) {
        Class<?> typeClass = typeMap.get(type);
        setObjectFactoryType(typeClass);
        objectFactory.createTopic(topicId);
    }

    public void createPartition(String topicId, String partitionId) {
        Topic<?> topic = getTopic(topicId);
        setObjectFactoryType(topic.getType());
        objectFactory.createPartition(topicId, partitionId);
    }

    public void createConsumerGroup(String groupId, String topicId, String rebalancing) {
        Topic<?> topic = getTopic(topicId);
        setObjectFactoryType(topic.getType());
        objectFactory.createConsumerGroup(groupId, topic, rebalancing);
    }

    public void createConsumer(String consumerId, String groupId) {
        ConsumerGroup<?> group = getConsumerGroup(groupId);
        Topic<?> topic = group.getAssignedTopic();
        setObjectFactoryType(topic.getType());
        objectFactory.createConsumer(consumerId, groupId);
    }

    public void createProducer(String producerId, String type, String allocation) {
        Class<?> typeClass = typeMap.get(type);
        setObjectFactoryType(typeClass);
        objectFactory.createProducer(producerId, type, allocation);
    }

    public void createEvent(String producerId, String topicId, String eventId, String partitionId) {
        Producer<?> producer = getProducer(producerId);
        Topic<?> topic = getTopic(topicId);
        if(producer == null || topic == null || !(topic.getType().equals(producer.getType()))) {
            System.out.println("Producer type does not match topic type.");
            return;
        }
        
        setObjectFactoryType(topic.getType());
        objectFactory.createEvent(producerId, topicId, eventId, partitionId);
    }

    public void deleteConsumer(String consumerId) {
        tributaryCluster.deleteConsumer(consumerId);
    }

    public void showTopic(String topicId) {
        Topic<?> topic = tributaryCluster.getTopic(topicId);
        if (topic != null) {
            topic.showTopic();
        } else {
            System.out.println("Topic not found: " + topicId);
        }
    }

    public void showGroup(String groupId) {
        ConsumerGroup<?> group = tributaryCluster.getConsumerGroup(groupId);
        if (group != null) {
            group.showGroup();
        } else {
            System.out.println("Group not found: " + groupId);
        }
    }

    private Consumer<?> findConsumer(String consumerId) {
        Consumer<?> specifiedConsumer = null;
        for (ConsumerGroup<?> group : tributaryCluster.listConsumerGroups()) {
            for (Consumer<?> consumer : group.listConsumers()) {
                if (consumer.getId().equals(consumerId)) {
                    specifiedConsumer = consumer;
                }
            }
        }
        return specifiedConsumer;
    }

    private Partition<?> findPartition(String partitionId) {
        Partition<?> specifiedPartition = null;
        for (Topic<?> topic : tributaryCluster.listTopics()) {
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

    /*
     * Consume events from a partition. The number of events to consume is specified
     * by numberOfEvents.
     * @precondition: The consumer must be assigned to the partition.
     */
    public void consumeEvents(String consumerId, String partitionId, int numberOfEvents) {
        Consumer<?> consumer = findConsumer(consumerId);
        Partition<?> partition = findPartition(partitionId);
        String topicId = partition.getAllocatedTopicId();
        Topic<?> topic = getTopic(topicId);

        if (consumer == null || partition == null || !consumer.listAssignedPartitions().contains(partition)) {
            System.out.println("Partition " + partitionId + " is not assigned to consumer " + consumerId);
            return;
        }

        if (topic.getType().equals(Integer.class)) {
            @SuppressWarnings("unchecked")
            Consumer<Integer> intConsumer = (Consumer<Integer>) consumer;
            @SuppressWarnings("unchecked")
            Partition<Integer> intPartition = (Partition<Integer>) partition;
            consumeHelper(Integer.class, intConsumer, intPartition, numberOfEvents);
        } else if (topic.getType().equals(String.class)) {
            @SuppressWarnings("unchecked")
            Consumer<String> strConsumer = (Consumer<String>) consumer;
            @SuppressWarnings("unchecked")
            Partition<String> strPartition = (Partition<String>) partition;
            consumeHelper(String.class, strConsumer, strPartition, numberOfEvents);
        }
    }

    public <T> void consumeHelper(Class<T> type, Consumer<T> consumer, Partition<T> partition, int numberOfEvents) {
        List<Message<T>> messages = partition.listMessages();
        int currentOffset = consumer.getOffset(partition);
        int count = 0;

        for (int i = currentOffset + 1; i < messages.size() && count < numberOfEvents; i++, count++) {
            consumer.consume(messages.get(i), partition);
        }

        if (count < numberOfEvents) {
            System.out.println("Not enough messages to consume. Consumed: " + count);
        } else {
            System.out.println("Consumed " + count + " messages.");
        }
    }

    public void updateRebalancing(String groupId, String rebalancing) {
        ConsumerGroup<?> group = getConsumerGroup(groupId);
        group.setRebalancingMethod(rebalancing);
        group.rebalance();
        System.out.println("Updated rebalancing strategy for group: " + groupId + " to " + rebalancing);
    }

}
