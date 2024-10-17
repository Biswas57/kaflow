package tributary.api.producers;

import java.util.List;

import org.json.JSONObject;

import tributary.api.Message;
import tributary.api.Partition;
import tributary.api.Topic;
import tributary.core.AdminObject;

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
