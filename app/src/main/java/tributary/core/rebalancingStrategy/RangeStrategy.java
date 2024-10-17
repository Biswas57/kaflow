package tributary.core.rebalancingStrategy;

import java.util.ArrayList;
import java.util.List;

import tributary.api.Consumer;
import tributary.api.Partition;
import tributary.api.Topic;

public class RangeStrategy<T> implements RebalancingStrategy<T> {
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
