package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class CreatePartitionRequest {
    private final String topicId;
    private final String partitionId;

    @JsonCreator
    public CreatePartitionRequest(
            @JsonProperty("topicId") String topicId,
            @JsonProperty("partitionId") String partitionId) {
        if (topicId == null || topicId.isEmpty()) {
            throw new IllegalArgumentException("topicId cannot be null or empty");
        }
        if (partitionId == null || partitionId.isEmpty()) {
            throw new IllegalArgumentException("partitionId cannot be null or empty");
        }
        this.topicId = topicId;
        this.partitionId = partitionId;
    }

    public String getTopicId() {
        return topicId;
    }

    public String getPartitionId() {
        return partitionId;
    }
}