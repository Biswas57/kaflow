package tributary.core.rebalancingStrategy;

import java.util.List;

import tributary.api.Consumer;
import tributary.api.Partition;

public class RangeStrategy<T> implements RebalancingStrategy<T> {
    @Override
    public void rebalance(List<Partition<T>> partitions, List<Consumer<T>> consumers) {
        for (Consumer<T> consumer : consumers) {
            consumer.clearAssignments();
        }

        int minPartitionsPerConsumer = partitions.size() / consumers.size();
        int extraPartitions = partitions.size() % consumers.size();

        int partitionIndex = 0;
        for (Consumer<T> consumer : consumers) {
            for (int j = 0; j < minPartitionsPerConsumer; j++, partitionIndex++) {
                consumer.assignPartition(partitions.get(partitionIndex));
            }
            if (extraPartitions > 0) {
                consumer.assignPartition(partitions.get(partitionIndex++));
                extraPartitions--;
            }
        }
    }
}
