package tributary.core.tributaryObject;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

import org.json.JSONObject;

import tributary.core.encryptionManager.EncryptionManager;
import tributary.core.typeHandlerFactory.TypeHandler;
import tributary.core.typeHandlerFactory.TypeHandlerFactory;

public class Message<T> extends TributaryObject {
    private LocalDateTime createdDate;
    private Class<T> payloadType;
    public long publicKey;
    private Map<String, String> content;
    private static final Map<String, Class<?>> typeMap = new HashMap<>();
    private static final EncryptionManager encryptionManager = new EncryptionManager();

    static {
        typeMap.put("integer", Integer.class);
        typeMap.put("string", String.class);
        typeMap.put("bytes", byte[].class);
    }

    public Message(String messageId, LocalDateTime createdDate, Class<T> payloadType, Map<String, String> content) {
        super(messageId);
        this.createdDate = createdDate;
        this.payloadType = payloadType;
        this.content = content;
        this.publicKey = encryptionManager.getPublicKey();
    }

    public static <T> Message<T> fromJson(JSONObject json, Class<T> prodType) {
        String messageId = json.getString("eventId");
        LocalDateTime createdDate = LocalDateTime.now();
        String type = json.optString("PayloadType").toLowerCase();

        @SuppressWarnings("unchecked")
        Class<T> jsonType = (Class<T>) typeMap.get(type); // Cast to Class<T>
        if (jsonType == null) {
            throw new IllegalArgumentException("Invalid payload type: " + type);
        } else if (jsonType != prodType) {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        JSONObject rawContents = json.getJSONObject("messageContents");
        Map<String, String> content = new LinkedHashMap<>();

        // Fetch the appropriate handler for the payload type
        TypeHandler<T> handler = TypeHandlerFactory.getHandler(prodType);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        // Process and encrypt each content item
        List<String> sortedKeys = new ArrayList<>(rawContents.keySet());

        for (String key : sortedKeys) {
            T rawValue = handler.handle(rawContents.get(key));
            String valueString = handler.valueToString(rawValue);
            String encrypted = encryptionManager.encrypt(valueString);
            content.put(key, encrypted);
        }

        return new Message<>(messageId, createdDate, prodType, content);
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
