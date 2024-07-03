package tributary.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Consumer<T> extends TributaryObject {
    private String groupId;
    private List<Partition<T>> assignedPartitions;
    private Map<Partition<T>, Integer> partitionOffsets; // Track last consumed offset for each partition

    public Consumer(String groupId, String consumerId) {
        super(consumerId);
        this.groupId = groupId;
        this.assignedPartitions = new ArrayList<>();
        this.partitionOffsets = new HashMap<>();
    }

    public void consume(Message<T> message, Partition<T> partition) {
        int currentOffset = getOffset(partition);
        partitionOffsets.put(partition, currentOffset);
        StringBuilder contentBuilder = new StringBuilder();

        for (Map.Entry<String, T> entry : message.getContent().entrySet()) {
            contentBuilder.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }

        if (contentBuilder.length() > 0) {
            contentBuilder.setLength(contentBuilder.length() - 1);
        }

        System.out.println("The event: " + message.getId() + " has been consumed by consumer " + getId()
                + ". It contains the contents:\n" + contentBuilder);
    }

    public String getGroup() {
        return groupId;
    }

    public void assignPartition(Partition<T> partition) {
        assignedPartitions.add(partition);
    }

    public void unassignPartition(String partitionId) {
        assignedPartitions.removeIf(p -> p.getId().equals(partitionId));
    }

    public List<Partition<T>> listAssignedPartitions() {
        return assignedPartitions;
    }

    public void clearAssignments() {
        this.assignedPartitions.clear();
    }

    public void updateOffset(Partition<T> partition, int newOffset) {
        partitionOffsets.put(partition, newOffset - 1);
    }

    public int getOffset(Partition<T> partition) {
        return partitionOffsets.getOrDefault(partition, -2) + 1;
    }
}
