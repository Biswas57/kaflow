package tributary.core.tributaryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tributary.core.encryptionManager.EncryptionManager;
import tributary.core.typeHandlerFactory.TypeHandler;
import tributary.core.typeHandlerFactory.TypeHandlerFactory;

public class Consumer<T> extends TributaryObject {
    private String groupId;
    private List<Partition<T>> assignedPartitions;
    private EncryptionManager encryptionManager = new EncryptionManager();

    public Consumer(String groupId, String consumerId) {
        super(consumerId);
        this.groupId = groupId;
        this.assignedPartitions = new ArrayList<>();
    }

    public void consume(Message<T> message, Partition<T> partition) {
        StringBuilder contentBuilder = new StringBuilder();
        TypeHandler<T> handler = TypeHandlerFactory.getHandler(message.getPayloadType());

        for (Map.Entry<String, String> entry : message.getContent().entrySet()) {
            String encrypted = entry.getValue();
            String decrypted = encryptionManager.decrypt(encrypted, message.getPublicKey());
            Object value = handler.stringToValue(decrypted);
            contentBuilder.append(entry.getKey()).append(" = ").append(handler.handle(value)).append("\n");
        }

        if (contentBuilder.length() > 0) {
            contentBuilder.setLength(contentBuilder.length() - 1);
        }

        System.out.println("The event: " + message.getId() + " has been consumed by consumer " + getId()
                + ". It contains the contents:\n" + contentBuilder);
        partition.setOffset(this, partition.getOffset(this) + 1);
    }

    public String getGroup() {
        return groupId;
    }

    public void assignPartition(Partition<T> partition) {
        assignedPartitions.add(partition);
        partition.setOffset(this, 0);
    }

    public void unassignPartition(String partitionId) {
        for (Partition<T> partition : assignedPartitions) {
            if (partition.getId().equals(partitionId)) {
                assignedPartitions.remove(partition);
                partition.removeOffset(this);
                return;
            }
        }
    }

    public List<Partition<T>> listAssignedPartitions() {
        return assignedPartitions;
    }

    public void clearAssignments() {
        this.assignedPartitions.clear();
    }
}
