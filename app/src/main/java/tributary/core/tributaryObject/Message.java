package tributary.core.tributaryObject;

import java.time.LocalDateTime;
import java.util.Map;

public class Message<T> extends TributaryObject {
    private LocalDateTime createdDate;
    public long publicKey;
    private Map<String, String> content;

    public Message(String messageId, LocalDateTime createdDate, Map<String, String> content,
            Long publicKey) {
        super(messageId);
        this.createdDate = createdDate;
        this.content = content;
        this.publicKey = publicKey;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public Map<String, String> getContent() {
        return content;
    }

    public long getPublicKey() {
        return publicKey;
    }
}
