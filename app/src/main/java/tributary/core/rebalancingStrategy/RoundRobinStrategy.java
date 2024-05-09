package tributary.core.rebalancingStrategy;

import java.util.List;

import tributary.api.Consumer;
import tributary.api.Partition;

public class RoundRobinStrategy<T> implements RebalancingStrategy<T> {
    @Override
    public void rebalance(List<Partition<T>> partitions, List<Consumer<T>> consumers) {
        for (Consumer<T> consumer : consumers) {
            consumer.clearAssignments();
        }

        for (int i = 0; i < partitions.size(); i++) {
            Consumer<T> consumer = consumers.get(i % consumers.size());
            consumer.assignPartition(partitions.get(i));
        }
    }
}
