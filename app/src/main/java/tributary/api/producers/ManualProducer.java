package tributary.api.producers;

import java.util.List;

import tributary.api.Message;
import tributary.api.Partition;

public class ManualProducer<T> extends Producer<T> {
    public ManualProducer(String producerId, Class<T> type) {
        super(producerId, type);
    }

    @Override
    public void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message) {
        for (Partition<T> p : partitions) {
            if (p.getId().equals(partitionId)) {
                if (p.listMessages().contains(message)) {
                    System.out.println("Message " + message.getId() + " already exists in " + partitionId + " partition");
                }
                p.addMessage(message);
                System.out.println(
                        "The event: " + message.getId() + " has been manually allocated to partition " 
                        + partitionId + "\n");
                return;
            }
        }
    }
}
