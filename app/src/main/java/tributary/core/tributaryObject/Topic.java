package tributary.core.tributaryObject;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

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

    public boolean containsPartition(String partitionId) {
        return partitions.stream().anyMatch(p -> p.getId().equals(partitionId));
    }

    public JSONObject showTopic() {
        JSONObject topicJson = new JSONObject();

        JSONArray partitionsArray = new JSONArray();
        for (Partition<T> partition : partitions) {
            JSONObject partitionJson = new JSONObject();

            JSONArray messagesArray = new JSONArray();
            for (Message<T> message : partition.listMessages()) {
                messagesArray.put(message.getId());
            }
            partitionJson.put("messages", messagesArray);
            partitionsArray.put(partitionJson);
            partitionJson.put("id", partition.getId());
        }
        topicJson.put("partitions", partitionsArray);
        topicJson.put("id", this.getId());

        return topicJson;
    }
}
