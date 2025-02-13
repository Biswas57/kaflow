package tributary.core.tributaryObject;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import tributary.core.rebalancingStrategy.RangeStrategy;
import tributary.core.rebalancingStrategy.RebalancingStrategy;
import tributary.core.rebalancingStrategy.RoundRobinStrategy;

public class ConsumerGroup<T> extends AdminObject<T> {
    private List<Consumer<T>> consumers;
    private RebalancingStrategy<T> rebalanceMethod;

    public ConsumerGroup(String groupId, Topic<T> assignedTopic, String rebalanceMethod) {
        super(groupId, assignedTopic.getType());
        this.consumers = new ArrayList<>();
        assignTopic(assignedTopic);
        setRebalancingMethod(rebalanceMethod);
    }

    public void setRebalancingMethod(String rebalanceMethod) {
        switch (rebalanceMethod) {
            case "roundrobin":
                this.rebalanceMethod = new RoundRobinStrategy<>();
                break;
            case "range":
                this.rebalanceMethod = new RangeStrategy<>();
                break;
            default:
                System.out.println("Unknown rebalancing strategy: " + rebalanceMethod);
                break;
        }
    }

    public RebalancingStrategy<T> getRebalanceMethod() {
        return rebalanceMethod;
    }

    public String getRebalanceMethodName() {
        return rebalanceMethod.getClass().getSimpleName();
    }

    public void addConsumer(Consumer<T> consumer) {
        if (!consumers.contains(consumer)) {
            consumers.add(consumer);
        } else {
            System.out.println("Consumer already exists in the group");
        }
    }

    public boolean containsConsumer(String consumerId) {
        return getConsumer(consumerId) != null;
    }

    public List<Consumer<T>> listConsumers() {
        return new ArrayList<>(consumers);
    }

    public Consumer<T> getConsumer(String consumerId) {
        return consumers.stream().filter(c -> c.getId().equals(consumerId)).findFirst().orElse(null);
    }

    public void removeConsumer(String consumerId) {
        consumers.removeIf(c -> c.getId().equals(consumerId));
        System.out.println("Deleted consumer with ID: " + consumerId);
        rebalance();
        showGroup();
    }

    public JSONObject showGroup() {
        // Create a JSON object to hold the consumer group details.
        JSONObject groupJson = new JSONObject();

        // Create an array to hold each consumer's details.
        JSONArray consumersArray = new JSONArray();
        for (Consumer<T> consumer : listConsumers()) {
            JSONObject consumerJson = new JSONObject();

            // For each consumer, add an array of assigned partition IDs.
            JSONArray partitionsArray = new JSONArray();
            for (Partition<T> partition : consumer.listAssignedPartitions()) {
                partitionsArray.put(partition.getId());
            }
            consumerJson.put("partitions", partitionsArray);
            consumersArray.put(consumerJson);
            consumerJson.put("id", consumer.getId());
        }
        groupJson.put("consumers", consumersArray);
        groupJson.put("id", this.getId());

        return groupJson;
    }

    public void rebalance() {
        rebalanceMethod.rebalance(getAssignedTopics(), listConsumers());
    }
}
