package tributary.core.dtoFinalBoss;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ParallelProduceRequest {
    private final List<String> producerIds;
    private final List<String> topicIds;
    private final List<Map<String, Object>> events;
    private final List<String> partitionIds;

    @JsonCreator
    public ParallelProduceRequest(
            @JsonProperty("producerIds") List<String> producerIds,
            @JsonProperty("topicIds") List<String> topicIds,
            @JsonProperty("events") List<Map<String, Object>> events,
            @JsonProperty("partitionIds") List<String> partitionIds) {
        if (producerIds == null || producerIds.isEmpty()) {
            throw new IllegalArgumentException("producerIds cannot be null or empty");
        }
        if (topicIds == null || topicIds.isEmpty()) {
            throw new IllegalArgumentException("topicIds cannot be null or empty");
        }
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events cannot be null or empty");
        }
        if (partitionIds == null || partitionIds.isEmpty()) {
            throw new IllegalArgumentException("partitionIds cannot be null or empty");
        }
        // Use defensive copies to preserve immutability
        this.producerIds = List.copyOf(producerIds);
        this.topicIds = List.copyOf(topicIds);
        this.events = List.copyOf(events);
        this.partitionIds = List.copyOf(partitionIds);
    }

    public List<String> getProducerIds() {
        return producerIds;
    }

    public List<String> getTopicIds() {
        return topicIds;
    }

    public List<Map<String, Object>> getEvents() {
        return events;
    }

    public List<String> getPartitionIds() {
        return partitionIds;
    }
}
