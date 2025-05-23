package tributary.core.rebalancingStrategy;

import java.util.List;

import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.Partition;

public interface RebalancingStrategy<T> {
    public void rebalance(List<Partition<T>> partitions, List<Consumer<T>> consumers);
}
