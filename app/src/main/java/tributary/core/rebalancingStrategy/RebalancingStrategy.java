package tributary.core.rebalancingStrategy;

import java.util.List;

import tributary.api.Consumer;
import tributary.api.Topic;

public interface RebalancingStrategy<T> {
    public void rebalance(List<Topic<T>> topics, List<Consumer<T>> consumers);
}
