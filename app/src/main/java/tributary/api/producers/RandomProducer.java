package tributary.api.producers;

import java.util.List;

import tributary.api.Message;
import tributary.api.Partition;

public class RandomProducer<T> extends Producer<T> {
    public RandomProducer(String producerId, Class<T> type) {
        super(producerId, type);
    }

    @Override
    public void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message) {
        int randomIndex = (int) (Math.random() * partitions.size());

        Partition<T> selectedPartition = partitions.get(randomIndex);
        selectedPartition.addMessage(message);
        System.out.println("The event: " + message.getId() + " has been randomly allocated to partition "
                + selectedPartition.getId());
    }
}
