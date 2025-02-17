package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class CreateConsumerGroupRequest {
    private final String groupId;
    private final String topicId;
    private final String rebalancing;

    @JsonCreator
    public CreateConsumerGroupRequest(
            @JsonProperty("groupId") String groupId,
            @JsonProperty("topicId") String topicId,
            @JsonProperty("rebalancing") String rebalancing) {
        if (groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("groupId cannot be null or empty");
        }
        if (topicId == null || topicId.isEmpty()) {
            throw new IllegalArgumentException("topicId cannot be null or empty");
        }
        if (rebalancing == null || rebalancing.isEmpty()) {
            throw new IllegalArgumentException("rebalancing cannot be null or empty");
        }
        this.groupId = groupId;
        this.topicId = topicId;
        this.rebalancing = rebalancing;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getTopicId() {
        return topicId;
    }

    public String getRebalancing() {
        return rebalancing;
    }
}