package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class UpdateRebalancingRequest {
    private final String groupId;
    private final String rebalancing;

    @JsonCreator
    public UpdateRebalancingRequest(
            @JsonProperty("groupId") String groupId,
            @JsonProperty("rebalancing") String rebalancing) {
        if (groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("groupId cannot be null or empty");
        }
        if (rebalancing == null || rebalancing.isEmpty()) {
            throw new IllegalArgumentException("rebalancing cannot be null or empty");
        }
        this.groupId = groupId;
        this.rebalancing = rebalancing;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getRebalancing() {
        return rebalancing;
    }
}
