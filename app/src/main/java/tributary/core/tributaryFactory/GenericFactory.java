package tributary.core.tributaryFactory;

import org.json.JSONObject;

import tributary.core.tributaryObject.*;
import tributary.core.tributaryObject.producers.*;

public class GenericFactory<T> implements ObjectFactory<T> {
    private final TributaryCluster cluster = TributaryCluster.getInstance();
    private final Class<T> type;

    public GenericFactory(Class<T> type) {
        this.type = type;
    }

    @Override
    public void createTopic(String topicId) {
        cluster.addTopic(new Topic<>(topicId, type));
    }

    @Override
    public void createPartition(String topicId, String partitionId) {
        Topic<T> topic = typedTopic(topicId);
        topic.addPartition(new Partition<>(topic, partitionId));
        cluster.listConsumerGroups()
                .forEach(ConsumerGroup::rebalance);
    }

    @Override
    public void createConsumerGroup(String groupId,
            String topicId,
            String rebalance) {
        Topic<T> topic = typedTopic(topicId);
        ConsumerGroup<T> group = new ConsumerGroup<>(groupId, topic, rebalance);
        cluster.addGroup(group);
    }

    @Override
    public void createConsumer(String groupId, String consumerId) {
        ConsumerGroup<T> group = typedGroup(groupId);
        group.addConsumer(new Consumer<>(groupId, consumerId));
        group.rebalance();
    }

    @Override
    public void createProducer(String producerId,
            String topicId,
            String allocation) {
        Topic<T> topic = typedTopic(topicId);
        Producer<T> p = switch (allocation) {
            case "manual" -> new ManualProducer<>(producerId, type, topic);
            case "random" -> new RandomProducer<>(producerId, type, topic);
            default -> throw new IllegalArgumentException("Bad allocation");
        };
        cluster.addProducer(p);
    }

    @Override
    public void createEvent(String producerId,
            String topicId,
            JSONObject event,
            String partitionId) {
        Producer<T> producer = typedProducer(producerId);
        Topic<T> topic = typedTopic(topicId);
        producer.produceMessage(topic.listPartitions(),
                partitionId,
                event);
    }

    /* ---------- typed helpers ---------- */
    private Topic<T> typedTopic(String id) {
        return cast(cluster.getTopic(id));
    }

    private ConsumerGroup<T> typedGroup(String id) {
        return cast(cluster.getConsumerGroup(id));
    }

    private Producer<T> typedProducer(String id) {
        return cast(cluster.getProducer(id));
    }

    @SuppressWarnings("unchecked")
    private <X> X cast(Object o) {
        return (X) o;
    }
}
