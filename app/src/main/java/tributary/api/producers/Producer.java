package tributary.api.producers;

import java.util.List;

import org.json.JSONObject;

import tributary.api.Message;
import tributary.api.Partition;
import tributary.api.TributaryObject;

public abstract class Producer<T> extends TributaryObject {
    private Class<T> type;

    public Producer(String producerId, Class<T> type) {
        super(producerId);
        this.type = type;
    }

    public Class<T> getType() {
        return type;
    }

    public abstract void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message);

    public void produceMessage(List<Partition<T>> partitions, String partitionId, JSONObject message) {
        Message<T> msg = Message.fromJson(message, getType());
        allocateMessage(partitions, partitionId, msg);
    }
}
