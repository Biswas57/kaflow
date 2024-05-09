package tributary.api;

import java.util.ArrayList;
import java.util.List;

public class Topic<T> extends TributaryObject {
    private Class<T> type;
    private List<Partition<T>> partitions;

    public Topic(String topicId, Class<T> type) {
        super(topicId);
        this.type = type;
        this.partitions = new ArrayList<>();
    }

    public Class<T> getType() {
        return type;
    }

    public boolean isIntegerTopic() {
        return Integer.class.equals(type);
    }

    public boolean isStringTopic() {
        return String.class.equals(type);
    }

    public void addPartition(Partition<T> partition) {
        partitions.add(partition);
    }

    public void removePartition(String partitionId) {
        partitions.removeIf(p -> p.getId().equals(partitionId));
    }

    public Partition<T> getPartition(String partitionId) {
        return partitions.stream().filter(p -> p.getId().equals(partitionId)).findFirst().orElse(null);
    }

    public List<Partition<T>> listPartitions() {
        return partitions;
    }

    public void showTopic() {
        System.out.println("Topic ID: " + getId());
        for (Partition<T> partition : partitions) {
            System.out.print("Partition ID: " + partition.getId() + " - Messages: ");
            partition.listMessages().forEach(m -> System.out.print(m.getId() + ", "));
            System.out.println();
        }
    }
}
