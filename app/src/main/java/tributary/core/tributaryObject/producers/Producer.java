package tributary.core.tributaryObject.producers;

import java.time.LocalDateTime;
import java.util.*;

import org.json.JSONObject;

import tributary.core.encryptionManager.EncryptionManager;
import tributary.core.tributaryObject.*;
import tributary.core.typeHandlerFactory.TypeHandler;
import tributary.core.typeHandlerFactory.TypeHandlerFactory;

public abstract class Producer<T> extends AdminObject<T> {
    private static final Map<String, Class<?>> typeMap = new HashMap<>();
    private static final EncryptionManager encryptionManager = new EncryptionManager();

    static {
        typeMap.put("integer", Integer.class);
        typeMap.put("string", String.class);
        typeMap.put("bytes", byte[].class);
    }

    public Producer(String producerId, Class<T> type, Topic<T> topic) {
        super(producerId, type);
        assignTopic(topic);
    }

    public abstract void allocateMessage(List<Partition<T>> partitions, String partitionId, Message<T> message);

    /**
     * Creates an event from the provided JSON object.
     * This method extracts the event details, encrypts the message contents,
     * creates a Message object, and delegates allocation to the abstract
     * allocateMessage method.
     *
     * @param partitions  The list of partitions available in the topic.
     * @param partitionId The target partition identifier (if applicable).
     * @param messageJson The JSON object representing the event.
     */
    public void produceMessage(List<Partition<T>> partitions, String partitionId, JSONObject messageJson) {
        // Extract event details from JSON.
        String messageId = messageJson.getString("eventId");
        LocalDateTime createdDate = LocalDateTime.now();
        String type = messageJson.optString("PayloadType").toLowerCase();

        @SuppressWarnings("unchecked")
        Class<T> jsonType = (Class<T>) typeMap.get(type);
        if (jsonType == null) {
            throw new IllegalArgumentException("Invalid payload type: " + type);
        } else if (!jsonType.equals(getType())) {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        JSONObject rawContents = messageJson.getJSONObject("messageContents");
        Map<String, String> content = new LinkedHashMap<>();

        // Get the handler for type conversion and encryption.
        TypeHandler<T> handler = TypeHandlerFactory.getHandler(getType());
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        // Process each key in the message content.
        List<String> sortedKeys = new ArrayList<>(rawContents.keySet());
        for (String key : sortedKeys) {
            T rawValue = handler.handle(rawContents.get(key));
            String valueString = handler.valueToString(rawValue);
            String encrypted = encryptionManager.encrypt(valueString);
            content.put(key, encrypted);
        }

        // Create the Message object and allocate it.
        Message<T> msg = new Message<T>(messageId, createdDate, content, encryptionManager.getPublicKey());
        allocateMessage(partitions, partitionId, msg);
    }

    public EncryptionManager getEncryptionmanager() {
        return encryptionManager;
    }
}
