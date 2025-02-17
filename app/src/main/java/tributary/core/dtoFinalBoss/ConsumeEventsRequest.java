package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ConsumeEventsRequest {
    private final String consumerId;
    private final String partitionId;
    private final int numberOfEvents;

    @JsonCreator
    public ConsumeEventsRequest(
            @JsonProperty("consumerId") String consumerId,
            @JsonProperty("partitionId") String partitionId,
            @JsonProperty("numberOfEvents") int numberOfEvents) {
        if (consumerId == null || consumerId.isEmpty()) {
            throw new IllegalArgumentException("consumerId cannot be null or empty");
        }
        if (partitionId == null || partitionId.isEmpty()) {
            throw new IllegalArgumentException("partitionId cannot be null or empty");
        }
        if (numberOfEvents <= 0) {
            throw new IllegalArgumentException("numberOfEvents must be positive");
        }
        this.consumerId = consumerId;
        this.partitionId = partitionId;
        this.numberOfEvents = numberOfEvents;
    }

    public String getConsumerId() {
        return consumerId;
    }

    public String getPartitionId() {
        return partitionId;
    }

    public int getNumberOfEvents() {
        return numberOfEvents;
    }
}