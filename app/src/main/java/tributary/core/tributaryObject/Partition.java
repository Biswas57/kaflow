package tributary.core.tributaryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class Partition<T> extends TributaryObject {
    private List<Message<T>> messages;
    private String allocatedTopic;
    private Map<Consumer<T>, Integer> offset;

    public Partition(String topicId, String partitionId) {
        super(partitionId);
        this.allocatedTopic = topicId;
        this.messages = new ArrayList<>();
        this.offset = new HashMap<>();
    }

    public void addMessage(Message<T> message) {
        messages.add(message);
    }

    public String getAllocatedTopicId() {
        return allocatedTopic;
    }

    public List<Message<T>> listMessages() {
        return messages;
    }

    public Message<T> getMessage(String messageId) {
        return messages.stream().filter(m -> m.getId().equals(messageId)).findFirst().orElse(null);
    }

    public void setOffset(Consumer<T> consumer, int offset) {
        this.offset.put(consumer, offset);
    }

    public void removeOffset(Consumer<T> consumer) {
        offset.remove(consumer);
    }

    public int getOffset(Consumer<T> consumer) {
        return offset.get(consumer);
    }

    public void listOffsets() {
        for (Map.Entry<Consumer<T>, Integer> entry : offset.entrySet()) {
            System.out.println("Consumer " + entry.getKey().getId() + " offset: " + entry.getValue());
        }
    }
}
