package tributary.core.tributaryFactory;
import tributary.api.ConsumerGroup;
import tributary.api.Topic;
import tributary.api.TributaryCluster;

public abstract class ObjectFactory {
    private TributaryCluster tributaryCluster;

    public ObjectFactory() {
        this.tributaryCluster = TributaryCluster.getInstance();
    }

    public TributaryCluster getCluster() {
        return tributaryCluster;
    }

    public abstract void createTopic(String topicId);

    public abstract void createPartition(String topicId, String partitionId);

    public void createConsumerGroup(String groupId, Topic<?> topic, String rebalancing) {
        ConsumerGroup<?> group = new ConsumerGroup<>(groupId, topic, rebalancing);
        tributaryCluster.addGroup(group);

        System.out.println("Created consumer group with ID: " + groupId + " for topic: " + topic.getId()
                + " with rebalancing strategy: " + rebalancing);
    }

    public abstract void createConsumer(String groupId, String consumerId);

    public abstract void createProducer(String producerId, String type, String allocation);

    public abstract void createEvent(String producerId, String topicId, String eventId, String partitionId);
}
