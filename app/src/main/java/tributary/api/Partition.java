package tributary.api;

import java.util.ArrayList;
import java.util.List;

public class Partition<T> extends TributaryObject {
    private List<Message<T>> messages;
    private String allocatedTopic;

    public Partition(String topicId, String partitionId) {
        super(partitionId);
        this.allocatedTopic = topicId;
        this.messages = new ArrayList<>();
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
}
