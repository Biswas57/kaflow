package tributary.core.tributaryObject.producers;

import java.util.List;

import tributary.core.tributaryObject.Message;
import tributary.core.tributaryObject.Partition;
import tributary.core.tributaryObject.Topic;

public class ManualProducer<T> extends Producer<T> {
    public ManualProducer(String producerId, Class<T> type, Topic<T> topic) {
        super(producerId, type, topic);
    }

    @Override
    public void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message) {
        for (Partition<T> p : partitions) {
            if (p.getId().equals(partitionId)) {
                if (p.listMessages().contains(message)) {
                    System.out.println("Message " + message.getId() + " already exists in "
                            + partitionId + " partition");
                }
                p.addMessage(message);
                System.out.println("The event: " + message.getId() + " has been manually allocated to partition "
                        + partitionId + "\n");
                return;
            }
        }
    }
}
