package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class CreateConsumerRequest {
    private final String groupId;
    private final String consumerId;

    @JsonCreator
    public CreateConsumerRequest(
            @JsonProperty("groupId") String groupId,
            @JsonProperty("consumerId") String consumerId) {
        if (groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("groupId cannot be null or empty");
        }
        if (consumerId == null || consumerId.isEmpty()) {
            throw new IllegalArgumentException("consumerId cannot be null or empty");
        }
        this.groupId = groupId;
        this.consumerId = consumerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getConsumerId() {
        return consumerId;
    }
}