package tributary.core.tributaryFactory;

import java.util.List;

import org.json.JSONObject;

import tributary.core.tributaryObject.producers.*;
import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.ConsumerGroup;
import tributary.core.tributaryObject.Partition;
import tributary.core.tributaryObject.Topic;
import tributary.core.tributaryObject.TributaryCluster;

public class StringFactory extends ObjectFactory {
    public StringFactory() {
        setCluster(TributaryCluster.getInstance());
    }

    @Override
    public void createTopic(String topicId) {
        Topic<String> topic = new Topic<>(topicId, String.class);
        getCluster().addTopic(topic);
        System.out.println("Created String topic with ID: " + topicId + "\n");
    }

    @Override
    public void createPartition(String topicId, String partitionId) {
        @SuppressWarnings("unchecked")
        Topic<String> topic = (Topic<String>) getCluster().getTopic(topicId);
        List<ConsumerGroup<?>> groups = getCluster().listConsumerGroups();
        topic.addPartition(new Partition<String>(topic, partitionId));
        for (ConsumerGroup<?> group : groups) {
            for (Topic<?> t : group.getAssignedTopics()) {
                if (t.getId() == topicId) {
                    group.rebalance();
                }
            }
        }
        System.out.println("Created partition with ID: " + partitionId + " for topic: " + topicId + "\n");
    }

    @Override
    public void createConsumer(String groupId, String consumerId) {
        @SuppressWarnings("unchecked")
        ConsumerGroup<String> group = (ConsumerGroup<String>) getCluster().getConsumerGroup(groupId);
        Consumer<String> consumer = new Consumer<>(groupId, consumerId);
        group.addConsumer(consumer);
        group.rebalance();
        System.out.println("Created consumer with ID: " + consumerId + " for group: " + groupId + "\n");
    }

    public void createConsumerGroup(String groupId, String topicId, String rebalancing) {
        @SuppressWarnings("unchecked")
        Topic<String> topic = (Topic<String>) getCluster().getTopic(topicId);
        if (topic == null) {
            System.out.println("Topic " + topicId + " does not exist.\n");
            return;
        }
        ConsumerGroup<String> group = new ConsumerGroup<>(groupId, topic, rebalancing);
        getCluster().addGroup(group);

        System.out.println("Created consumer group with ID: " + groupId + " for topic: " + topic.getId()
                + " with " + rebalancing + " rebalancing strategy.\n");
    }

    @Override
    public void createProducer(String producerId, String topicId, String allocation) {
        @SuppressWarnings("unchecked")
        Topic<String> topic = (Topic<String>) getCluster().getTopic(topicId);
        Producer<String> producer;
        switch (allocation) {
            case "manual":
                producer = new ManualProducer<>(producerId, String.class, topic);
                break;
            case "random":
                producer = new RandomProducer<>(producerId, String.class, topic);
                break;
            default:
                System.out.println("Unsupported allocation type: " + allocation + "\n");
                return;
        }
        getCluster().addProducer(producer);
        System.out.println("Created producer with ID: " + producerId
                + " that produces String events with " + allocation + " allocation\n");
    }

    @Override
    public void createEvent(String producerId, String topicId, JSONObject event, String partitionId) {
        JSONObject message = event;
        @SuppressWarnings("unchecked")
        Producer<String> producer = (Producer<String>) getCluster().getProducer(producerId);
        @SuppressWarnings("unchecked")
        Topic<String> topic = (Topic<String>) getCluster().getTopic(topicId);
        producer.produceMessage(topic.listPartitions(), partitionId, message);
    }
}
