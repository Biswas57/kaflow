package tributary.core.tributaryObject;

import java.time.LocalDateTime;
import java.util.Objects;

public class Message<T> extends TributaryObject {
    private LocalDateTime createdAt;
    private Class<T> payloadType;
    private byte[] key;
    private T payload;

    public Message(String messageId, LocalDateTime createdAt, Class<T> payloadType, byte[] key, T payload) {
        super(messageId);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.payloadType = Objects.requireNonNull(payloadType);
        this.key = Objects.requireNonNull(key);
        this.payload = Objects.requireNonNull(payload);
    }

    public LocalDateTime getCreatedTime() {
        return createdAt;
    }

    public T getPayload() {
        return payload;
    }

    public Class<T> getPayloadType() {
        return payloadType;
    }

    public byte[] getKey() {
        return key;
    }
}
