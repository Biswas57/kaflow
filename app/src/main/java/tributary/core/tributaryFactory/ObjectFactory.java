package tributary.core.tributaryFactory;

public interface ObjectFactory<T> {
    public abstract void createTopic(String topicId);

    public abstract void createPartition(String topicId, String partitionId);

    public abstract void createConsumerGroup(String groupId, String topicId, String rebalancing);

    public abstract void createConsumer(String groupId, String consumerId);

    public abstract void createProducer(String producerId, String topicId, String allocation);
}
