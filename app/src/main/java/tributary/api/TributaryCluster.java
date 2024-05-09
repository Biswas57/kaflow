package tributary.api;

import java.util.ArrayList;
import java.util.List;

import tributary.api.producers.Producer;

public class TributaryCluster {
    private static TributaryCluster instance;
    private List<Topic<?>> topics;
    private List<ConsumerGroup<?>> consumerGroups;
    private List<Producer<?>> producers;

    private TributaryCluster() {
        this.topics = new ArrayList<>();
        this.consumerGroups = new ArrayList<>();
        this.producers = new ArrayList<>();
    }

    public static synchronized TributaryCluster getInstance() {
        if (instance == null) {
            instance = new TributaryCluster();
        }

        return instance;
    }

    public void addTopic(Topic<?> topic) {
        topics.add(topic);
    }

    public void addProducer(Producer<?> producer) {
        producers.add(producer);
    }

    public void addGroup(ConsumerGroup<?> group) {
        consumerGroups.add(group);
    }

    public void removeTopic(String topicId) {
        topics.removeIf(t -> t.getId().equals(topicId));
    }

    public Topic<?> getTopic(String topicId) {
        return topics.stream().filter(t -> t.getId().equals(topicId)).findFirst().orElse(null);
    }

    public ConsumerGroup<?> getConsumerGroup(String groupId) {
        return consumerGroups.stream().filter(g -> g.getId().equals(groupId)).findFirst().orElse(null);
    }

    public Producer<?> getProducer(String producerId) {
        return producers.stream().filter(p -> p.getId().equals(producerId)).findFirst().orElse(null);
    }

    public List<Topic<?>> listTopics() {
        return new ArrayList<>(topics);
    }

    public List<ConsumerGroup<?>> listConsumerGroups() {
        return new ArrayList<>(consumerGroups);
    }

    public void deleteConsumer(String consumerId) {
        for (ConsumerGroup<?> group : listConsumerGroups()) {
            for (Consumer<?> consumer : group.listConsumers()) {
                if (consumer.getId().equals(consumerId)) {
                    group.removeConsumer(consumerId);
                    return;
                }
            }
        }
        System.out.println("Consumer not found with ID: " + consumerId);
    }
}
