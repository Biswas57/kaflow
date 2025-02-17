package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class UpdateOffsetRequest {
    private final String consumerId;
    private final String partitionId;
    private final int offset;

    @JsonCreator
    public UpdateOffsetRequest(
            @JsonProperty("consumerId") String consumerId,
            @JsonProperty("partitionId") String partitionId,
            @JsonProperty("offset") int offset) {
        if (consumerId == null || consumerId.isEmpty()) {
            throw new IllegalArgumentException("consumerId cannot be null or empty");
        }
        if (partitionId == null || partitionId.isEmpty()) {
            throw new IllegalArgumentException("partitionId cannot be null or empty");
        }
        // offset can be any integer value (negative values allowed for backtracking,
        // etc.)
        this.consumerId = consumerId;
        this.partitionId = partitionId;
        this.offset = offset;
    }

    public String getConsumerId() {
        return consumerId;
    }

    public String getPartitionId() {
        return partitionId;
    }

    public int getOffset() {
        return offset;
    }
}