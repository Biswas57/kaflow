package tributary.core.tributaryFactory;

import java.io.IOException;

import tributary.api.ConsumerGroup;
import tributary.api.Topic;
import tributary.api.TributaryCluster;

public abstract class ObjectFactory {
    private TributaryCluster cluster = TributaryCluster.getInstance();

    public TributaryCluster getCluster() {
        return cluster;
    }

    public void setCluster(TributaryCluster cluster) {
        this.cluster = cluster;
    }

    public abstract void createTopic(String topicId);

    public abstract void createPartition(String topicId, String partitionId);

    public void createConsumerGroup(String groupId, Topic<?> topic, String rebalancing) {
        ConsumerGroup<?> group = new ConsumerGroup<>(groupId, topic, rebalancing);
        cluster.addGroup(group);

        System.out.println("Created consumer group with ID: " + groupId + " for topic: " + topic.getId()
                + " with " + rebalancing + " rebalancing strategy.\n");
    }

    public abstract void createConsumer(String groupId, String consumerId);

    public abstract void createProducer(String producerId, String type, String allocation);

    public abstract void createEvent(String producerId, String topicId,
            String eventId, String partitionId) throws IOException;
}
