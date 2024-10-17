package tributary.core.tributaryFactory;

import java.io.IOException;

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

    public abstract void createConsumerGroup(String groupId, String topicId, String rebalancing);

    public abstract void createConsumer(String groupId, String consumerId);

    public abstract void createProducer(String producerId, String topicId, String allocation);

    public abstract void createEvent(String producerId, String topicId,
            String eventId, String partitionId) throws IOException;
}
