package tributary.core.tributaryObject.producers;

import java.time.LocalDateTime;
import java.util.*;

import tributary.core.tributaryObject.*;
import tributary.core.util.Hash;

public abstract class Producer<T> extends TributaryObject {
    private Topic<T> topic;
    private Class<T> type;

    public Producer(String producerId, Class<T> type, Topic<T> topic) {
        super(producerId);
        this.topic = topic;
        this.type = type;
    }

    public abstract void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message);

    /**
     * Produces a message to the specified partition and allocates it to the
     * appropriate partition.
     * 
     * @param partitions  The list of partitions to which the message can be
     *                    allocated.
     * @param partitionId The ID of the partition to which the message is allocated.
     * @param key         The key associated with the message.
     * @param payload     The payload of the message.
     * @param createdAt   The creation date of the message.
     * @return The allocated message's Id.
     */
    public String produceMessage(List<Partition<T>> partitions, String partitionId, Class<T> type, byte[] key,
            T payload,
            LocalDateTime createdAt) {
        // Extract event details.
        String messageId = Hash.hash(key);
        LocalDateTime createdDate = createdAt;

        // Create the Message object and allocate it.
        Message<T> msg = new Message<T>(messageId, createdDate, type, key, payload);
        allocateMessage(partitions, partitionId, msg);

        return messageId;
    }

    public Topic<T> getTopic() {
        return topic;
    }

    public Class<T> getType() {
        return type;
    }
}
