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

                // while message with same id exists increase counter
                int counter = 1;
                String baseId = message.getId();
                boolean exists = true;

                while (exists) {
                    exists = false;
                    for (Message<T> m : p.listMessages()) {
                        if (m.getId().equals(message.getId())) {
                            exists = true;
                            break;
                        }
                    }
                    if (exists) {
                        message.setId(baseId + "." + counter);
                        counter++;
                    }
                }

                p.addMessage(message);
                System.out.println("The event " + message.getId() + " has been manually allocated to partition "
                        + partitionId + "\n");
                return;
            }
        }
    }
}
