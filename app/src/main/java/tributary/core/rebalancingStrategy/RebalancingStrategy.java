package tributary.core.rebalancingStrategy;

import java.util.List;

import tributary.api.Consumer;
import tributary.api.Partition;

public interface RebalancingStrategy<T> {
    public void rebalance(List<Partition<T>> partitions, List<Consumer<T>> consumers);
}
