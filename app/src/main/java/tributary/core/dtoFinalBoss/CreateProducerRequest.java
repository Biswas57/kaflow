package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class CreateProducerRequest {
    private final String producerId;
    private final String topicId;
    private final String allocation;

    @JsonCreator
    public CreateProducerRequest(
            @JsonProperty("producerId") String producerId,
            @JsonProperty("topicId") String topicId,
            @JsonProperty("allocation") String allocation) {
        if (producerId == null || producerId.isEmpty()) {
            throw new IllegalArgumentException("producerId cannot be null or empty");
        }
        if (topicId == null || topicId.isEmpty()) {
            throw new IllegalArgumentException("topicId cannot be null or empty");
        }
        if (allocation == null || allocation.isEmpty()) {
            throw new IllegalArgumentException("allocation cannot be null or empty");
        }
        this.producerId = producerId;
        this.topicId = topicId;
        this.allocation = allocation;
    }

    public String getProducerId() {
        return producerId;
    }

    public String getTopicId() {
        return topicId;
    }

    public String getAllocation() {
        return allocation;
    }
}