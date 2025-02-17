package tributary.core.tributaryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import javafx.util.Pair;
import tributary.core.encryptionManager.EncryptionManager;
import tributary.core.typeHandlerFactory.TypeHandler;
import tributary.core.typeHandlerFactory.TypeHandlerFactory;

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
    public JSONObject consume(Message<T> message, Partition<T> partition) {
        // Create an Encryption manager with the same keys as this partition to ensure
        // correct decryption
        Pair<Long, Long> keyPair = partition.getAllocatedTopic().getPrivateKey(partition.getId());
        EncryptionManager em = new EncryptionManager(keyPair);

        // Create a JSON object to hold decrypted content
        JSONObject contentJson = new JSONObject();
        Class<T> type = message.getPayloadType();
        TypeHandler<T> handler = TypeHandlerFactory.getHandler(type);

        // For each entry in the message content, decrypt the value and add it to the
        // JSON object
        for (Map.Entry<String, String> entry : message.getContent().entrySet()) {
            String encrypted = entry.getValue();
            String decrypted = em.decrypt(encrypted, message.getPublicKey());
            Object value = handler.handle(decrypted);
            contentJson.put(entry.getKey(), value);
        }

        // Update partition offset for this consumer
        partition.setOffset(this, partition.getOffset(this) + 1);

        // Include header as part of return data
        JSONObject headerJson = new JSONObject();
        headerJson.put("messageId", message.getId());
        headerJson.put("createdDate", message.getCreatedDate().toString());
        headerJson.put("payloadType", type.getSimpleName());

        // Create the result JSON object
        JSONObject result = new JSONObject();
        result.put("header", headerJson);
        result.put("content", contentJson);

        return result;
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
