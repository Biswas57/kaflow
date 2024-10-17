package tributary.core.rebalancingStrategy;

import java.util.ArrayList;
import java.util.List;

import tributary.api.Consumer;
import tributary.api.Partition;
import tributary.api.Topic;

public class RoundRobinStrategy<T> implements RebalancingStrategy<T> {
    @Override
    public void rebalance(List<Topic<T>> topics, List<Consumer<T>> consumers) {
        if (topics.isEmpty() || consumers.isEmpty()) {
            return;
        }
        for (Consumer<T> consumer : consumers) {
            consumer.clearAssignments();
        }

        List<Partition<T>> partitions = new ArrayList<>();
        for (Topic<T> topic : topics) {
            partitions.addAll(topic.listPartitions());
        }

        for (int i = 0; i < partitions.size(); i++) {
            Consumer<T> consumer = consumers.get(i % consumers.size());
            consumer.assignPartition(partitions.get(i));
        }
    }
}
