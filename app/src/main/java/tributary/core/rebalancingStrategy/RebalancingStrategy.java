package tributary.core.rebalancingStrategy;

import java.util.List;

import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.Topic;

public interface RebalancingStrategy<T> {
    public void rebalance(List<Topic<T>> topics, List<Consumer<T>> consumers);
}
