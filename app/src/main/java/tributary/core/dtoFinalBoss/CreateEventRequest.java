package tributary.core.dtoFinalBoss;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class CreateEventRequest {
    private final String producerId;
    private final String topicId;
    private final Map<String, Object> event;
    private final String partitionId; // optional

    @JsonCreator
    public CreateEventRequest(
            @JsonProperty("producerId") String producerId,
            @JsonProperty("topicId") String topicId,
            @JsonProperty("event") Map<String, Object> event,
            @JsonProperty("partitionId") String partitionId) {
        if (producerId == null || producerId.isEmpty()) {
            throw new IllegalArgumentException("producerId cannot be null or empty");
        }
        if (topicId == null || topicId.isEmpty()) {
            throw new IllegalArgumentException("topicId cannot be null or empty");
        }
        if (event == null || event.isEmpty()) {
            throw new IllegalArgumentException("event cannot be null or empty");
        }
        this.producerId = producerId;
        this.topicId = topicId;
        this.event = event;
        this.partitionId = partitionId; // optional field, may be null or empty
    }

    public String getProducerId() {
        return producerId;
    }

    public String getTopicId() {
        return topicId;
    }

    public Map<String, Object> getEvent() {
        return event;
    }

    public String getPartitionId() {
        return partitionId;
    }
}