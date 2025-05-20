package tributary.core.tributaryObject.producers;

import java.util.List;
import java.util.Map;

import tributary.core.util.Pair;
import tributary.core.encryptionManager.EncryptionManager;
import tributary.core.tributaryObject.Message;
import tributary.core.tributaryObject.Partition;
import tributary.core.tributaryObject.Topic;

public class RandomProducer<T> extends Producer<T> {
    public RandomProducer(String producerId, Class<T> type, Topic<T> topic) {
        super(producerId, type, topic);
    }

    @Override
    public void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message) {
        int randomIndex = (int) (Math.random() * partitions.size());
        Partition<T> p = partitions.get(randomIndex);

        // Create private key for Partition if not already created
        EncryptionManager em = getEncryptionmanager();
        Topic<T> t = p.getAllocatedTopic();
        Map<String, Pair<Long, Long>> keyMap = t.getKeyMap();
        if (!keyMap.containsKey(partitionId)) {
            keyMap.put(partitionId, em.getPrimePair());
        }

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
        System.out.println("The event: " + message.getId() + " has been randomly allocated to partition "
                + p.getId() + ".\n");
    }
}
