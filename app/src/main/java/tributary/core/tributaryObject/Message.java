package tributary.core.tributaryObject;

import java.time.LocalDateTime;
import java.util.Map;

public class Message<T> extends TributaryObject {
    private LocalDateTime createdDate;
    private Class<T> payloadType;
    public long publicKey;
    private Map<String, String> content;

    public Message(String messageId, LocalDateTime createdDate, Class<T> payloadType, Map<String, String> content,
            Long publicKey) {
        super(messageId);
        this.createdDate = createdDate;
        this.payloadType = payloadType;
        this.content = content;
        this.publicKey = publicKey;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public Class<T> getPayloadType() { // Return type is now Class<T>
        return payloadType;
    }

    public Map<String, String> getContent() {
        return content;
    }

    public long getPublicKey() {
        return publicKey;
    }
}
