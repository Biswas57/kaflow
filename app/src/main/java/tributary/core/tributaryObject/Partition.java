package tributary.core.tributaryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class Partition<T> extends TributaryObject {
    private List<Message<T>> messages;
    private Topic<T> allocatedTopic;
    private Map<String, Integer> offset;

    public Partition(Topic<T> topicId, String partitionId) {
        super(partitionId);
        this.allocatedTopic = topicId;
        this.messages = new ArrayList<>();
        this.offset = new HashMap<>();
    }

    public void addMessage(Message<T> message) {
        messages.add(message);
    }

    public Topic<T> getAllocatedTopic() {
        return allocatedTopic;
    }

    public List<Message<T>> listMessages() {
        return messages;
    }

    public Message<T> getMessage(String messageId) {
        return messages.stream().filter(m -> m.getId().equals(messageId)).findFirst().orElse(null);
    }

    public void setOffset(String groupId, int offset) {
        this.offset.put(groupId, offset);
    }

    public int getOffset(String groupId) {
        return offset.getOrDefault(groupId, 0);
    }

    public void listOffsets() {
        for (Map.Entry<String, Integer> entry : offset.entrySet()) {
            System.out.println("Consumer " + entry + " offset: " + entry.getValue());
        }
    }
}
