package tributary.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tributary.core.tributaryObject.producers.*;
import tributary.core.tributaryFactory.*;
import tributary.core.tributaryObject.AdminObject;
import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.ConsumerGroup;
import tributary.core.tributaryObject.Message;
import tributary.core.tributaryObject.Partition;
import tributary.core.tributaryObject.Topic;
import tributary.core.tributaryObject.TributaryCluster;

public class TributaryHelper {
    private TributaryCluster cluster;
    private ObjectFactory objectFactory;
    private Map<String, Class<?>> typeMap;

    public TributaryHelper() {
        this.cluster = TributaryCluster.getInstance();
        this.objectFactory = new StringFactory();
        this.typeMap = new HashMap<>();
        typeMap.put("integer", Integer.class);
        typeMap.put("string", String.class);
        typeMap.put("bytes", byte[].class);
    }

    /*
     * General Helper methods used by most other methods.
     * Involved in the the creation, deletion and manipulation of
     * Tributary objects.
     */
    public Topic<?> getTopic(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        return topic;
    }

    public String getTopicType(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        return topic.getType().getSimpleName().toLowerCase();
    }

    public ConsumerGroup<?> getConsumerGroup(String groupId) {
        ConsumerGroup<?> group = cluster.getConsumerGroup(groupId);
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

    public ObjectFactory getFactory() {
        return objectFactory;
    }

    public TributaryCluster getCluster() {
        return cluster;
    }

    /*
     * All creation method Helpers.
     * These Helper methods are specific to the process of creating Tributary
     * Objects.
     */

    public boolean verifyProducer(Producer<?> prod, Topic<?> topic) {
        String adminToken = cluster.getAdminProdToken();
        if (prod.listAssignedTopics().contains(topic)) {
            return true;
        } else if (adminToken != null && prod.getToken() != null) {
            if (adminToken.equals(prod.getToken()))
                return true;
        }

        return false;
    }

    /*
     * These Consumer helper methods are used to consume events from a partition.
     * They are specific to the process of consuming events.
     */

    public <T> void consumeEventsGeneric(Consumer<?> consumer, Partition<?> partition, Class<T> type,
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
        int currentOffset = partition.getOffset(consumer);
        int count = 0;

        for (int i = currentOffset; i < messages.size() && count < numberOfEvents; i++, count++) {
            consumer.consume(messages.get(i), partition);
        }

        if (count < numberOfEvents) {
            System.out.println("Not enough messages to consume.\nConsumed " + count + " messages.\n");
        } else {
            System.out.println("Consumed " + count + " messages.\n");
        }
    }

    public boolean verifyConsumer(Consumer<?> consumer, Topic<?> topic) {
        ConsumerGroup<?> group = getConsumerGroup(consumer.getGroup());
        String adminToken = cluster.getAdminConsToken();
        if (group.listAssignedTopics().contains(topic)) {
            return true;
        } else if (adminToken != null && group.getToken() != null) {
            if (adminToken.equals(group.getToken()))
                return true;
        }
        return false;
    }

    /*
     * All update helper methods.
     * These methods are used in assistance to update the state of Tributary
     * objects.
     */

    public <T> void updatePartitionOffsetGeneric(Consumer<?> consumer, Partition<?> partition,
            int offset) {
        @SuppressWarnings("unchecked")
        Consumer<T> typedconsumer = (Consumer<T>) consumer;
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;
        updateTypedConsumerOffset(typedconsumer, typedPartition, offset);
    }

    private <T> void updateTypedConsumerOffset(Consumer<T> consumer, Partition<T> partition, int offset) {
        // uses 1 indexing here because zero indexing is used in the consume method
        if (Math.abs(offset) > partition.getOffset(consumer)) {
            System.out.println(
                    "Playback or Backtrack Offset cannot be greater than the number of messages in the partition.\n");
            return;
            // if number is 0 return the last message in the partition
        } else if (offset == 0) {
            partition.setOffset(consumer, partition.listMessages().size());
            // if number negative return the last nth message
        } else if (offset < 0) {
            partition.setOffset(consumer, partition.listMessages().size() + offset + 1);
            // if number positive return the message at nth position in partition
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
     * All parallel produce and consumer Helper methods.
     * These methods are specifically used to assist in the parallel production and
     * consumption of events.
     */

    public <T> int parallelConsumerOffset(Consumer<?> consumer, Partition<?> partition, Class<T> type) {
        @SuppressWarnings("unchecked")
        Consumer<T> typedconsumer = (Consumer<T>) consumer;
        @SuppressWarnings("unchecked")
        Partition<T> typedPartition = (Partition<T>) partition;

        try {
            return typedPartition.getOffset(typedconsumer);
        } catch (NullPointerException e) {
            typedPartition.setOffset(typedconsumer, 0);
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
