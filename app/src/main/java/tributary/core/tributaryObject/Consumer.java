package tributary.core.tributaryObject;

import java.util.ArrayList;
import java.util.List;

public class Consumer<T> extends TributaryObject {
    private String groupId;
    private List<Partition<T>> assignedPartitions;

    public Consumer(String groupId, String consumerId) {
        super(consumerId);
        this.groupId = groupId;
        this.assignedPartitions = new ArrayList<>();
    }

    /**
     * Consumes a message from the specified partition, decrypts its content, and
     * returns the
     * result as a JSONObject.
     *
     * @param message   The message to be consumed.
     * @param partition The partition from which the message is consumed.
     * @return A JSONObject containing the message id, creation date, decrypted
     *         content,
     *         and the id of the consuming consumer.
     */
    public T consume(Message<T> message, Partition<T> partition) {
        // Update partition offset for this consumer
        partition.setOffset(this, partition.getOffset(this) + 1);
        return message.getPayload();
    }

    public String getGroup() {
        return groupId;
    }

    public void assignPartition(Partition<T> partition) {
        assignedPartitions.add(partition);
        partition.setOffset(this, 0);
    }

    public void unassignPartition(String partitionId) {
        Partition<T> partition = getPartition(partitionId);
        assignedPartitions.remove(partition);
        partition.removeOffset(this);
        return;
    }

    public List<Partition<T>> listAssignedPartitions() {
        return assignedPartitions;
    }

    public void clearAssignments() {
        this.assignedPartitions.clear();
    }

    public Partition<T> getPartition(String partitionId) {
        for (Partition<T> partition : assignedPartitions) {
            if (partition.getId().equals(partitionId)) {
                return partition;
            }
        }
        return null;
    }
}
