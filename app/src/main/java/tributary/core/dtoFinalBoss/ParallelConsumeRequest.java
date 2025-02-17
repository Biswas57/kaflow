package tributary.core.dtoFinalBoss;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ParallelConsumeRequest {
    private final List<String> consumerIds;
    private final List<String> partitionIds;
    private final List<Integer> numEvents;

    @JsonCreator
    public ParallelConsumeRequest(
            @JsonProperty("consumerIds") List<String> consumerIds,
            @JsonProperty("partitionIds") List<String> partitionIds,
            @JsonProperty("numEvents") List<Integer> numEvents) {
        if (consumerIds == null || consumerIds.isEmpty()) {
            throw new IllegalArgumentException("consumerIds cannot be null or empty");
        }
        if (partitionIds == null || partitionIds.isEmpty()) {
            throw new IllegalArgumentException("partitionIds cannot be null or empty");
        }
        if (numEvents == null || numEvents.isEmpty()) {
            throw new IllegalArgumentException("numEvents cannot be null or empty");
        }
        this.consumerIds = List.copyOf(consumerIds);
        this.partitionIds = List.copyOf(partitionIds);
        this.numEvents = List.copyOf(numEvents);
    }

    public List<String> getConsumerIds() {
        return consumerIds;
    }

    public List<String> getPartitionIds() {
        return partitionIds;
    }

    public List<Integer> getNumEvents() {
        return numEvents;
    }
}