package tributary.api;

import java.util.ArrayList;
import java.util.List;

import tributary.core.rebalancingStrategy.RangeStrategy;
import tributary.core.rebalancingStrategy.RebalancingStrategy;
import tributary.core.rebalancingStrategy.RoundRobinStrategy;

public class ConsumerGroup<T> extends TributaryObject {
    private List<Consumer<T>> consumers;
    private Topic<T> assignedTopic;
    private RebalancingStrategy<T> rebalanceMethod;

    public ConsumerGroup(String consumerGroupId, Topic<T> assignedTopic, String rebalanceMethod) {
        super(consumerGroupId);
        this.consumers = new ArrayList<>();
        this.assignedTopic = assignedTopic;
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

    public Topic<T> getAssignedTopic() {
        return assignedTopic;
    }

    public RebalancingStrategy<T> getRebalanceMethod() {
        return rebalanceMethod;
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

    public void showGroup() {
        System.out.println("Consumer Group ID: " + getId());
        List<Consumer<T>> consumers = listConsumers();
        for (Consumer<T> consumer : consumers) {
            System.out.print("Consumer ID: " + consumer.getId() + " - Assigned Partitions: ");
            consumer.listAssignedPartitions().forEach(p -> System.out.print(p.getId() + ", "));
            System.out.println();
        }
        System.out.println("\n--------------------------------------------------\n");
    }

    public void rebalance() {
        rebalanceMethod.rebalance(getAssignedTopic().listPartitions(), listConsumers());
    }
}
