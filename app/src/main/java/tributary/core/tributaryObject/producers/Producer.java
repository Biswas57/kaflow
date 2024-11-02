package tributary.core.tributaryObject.producers;

import java.util.List;

import org.json.JSONObject;

import tributary.core.tributaryObject.AdminObject;
import tributary.core.tributaryObject.Message;
import tributary.core.tributaryObject.Partition;
import tributary.core.tributaryObject.Topic;

public abstract class Producer<T> extends AdminObject<T> {

    public Producer(String producerId, Class<T> type, Topic<T> topic) {
        super(producerId, type);
        assignTopic(topic);
    }

    public abstract void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message);

    public void produceMessage(List<Partition<T>> partitions, String partitionId, JSONObject message) {
        Message<T> msg = Message.fromJson(message, getType());
        allocateMessage(partitions, partitionId, msg);
    }
}
