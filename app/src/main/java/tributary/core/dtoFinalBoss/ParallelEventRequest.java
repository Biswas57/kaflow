package tributary.core.dtoFinalBoss;

import java.util.List;
import java.util.Map;

/**
 * A data transfer object (DTO) that encapsulates all necessary information
 * for producing events in parallel. This class groups together arrays (or
 * lists)
 * of producer IDs, topic IDs, event data, and partition IDs.
 *
 * This improves maintainability by replacing multiple separate arrays with a
 * single
 * cohesive object.
 */
public class ParallelEventRequest {

    /**
     * The events field is a list of maps representing each event's data.
     * Each map corresponds to an event and contains key-value pairs that represent
     * the event content.
     */
    private List<String> partitions;

    // Parallel Produce Params
    private List<String> producers;
    private List<String> topics;
    private List<Map<String, Object>> events;

    // Parallel Consume Params
    private List<String> consumers;
    private List<Integer> numEvents;

    /**
     * Default constructor.
     */
    public ParallelEventRequest() {
        // Default constructor needed for JSON deserialization
    }

    /**
     * @param producers  List of producer IDs.
     * @param topics     List of topic IDs.
     * @param events     List of event objects represented as maps.
     * @param partitions List of partition IDs.
     */
    public ParallelEventRequest(List<String> producerIds, List<String> topicIds,
            List<Map<String, Object>> events, List<String> partitionIds) {
        this.producers = producerIds;
        this.topics = topicIds;
        this.events = events;
        this.partitions = partitionIds;
    }

    /**
     * @param consumerIds  List of consumer IDs.
     * @param partitionIds List of partition IDs.
     * @param numEvents    List of numbers specifying how many events to consume for
     *                     each consumer.
     */
    public ParallelEventRequest(List<String> consumerIds, List<String> partitionIds, List<Integer> numEvents) {
        this.consumers = consumerIds;
        this.partitions = partitionIds;
        this.numEvents = numEvents;
    }

    public List<String> getProducers() {
        return producers;
    }

    public void setProducers(List<String> producers) {
        this.producers = producers;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public List<Map<String, Object>> getEvents() {
        return events;
    }

    public void setEvents(List<Map<String, Object>> events) {
        this.events = events;
    }

    public List<String> getPartitions() {
        return partitions;
    }

    public void setPartitions(List<String> partitions) {
        this.partitions = partitions;
    }

    public List<String> getConsumers() {
        return consumers;
    }

    public List<Integer> getNumEvents() {
        return numEvents;
    }

    @Override
    public String toString() {
        return "EventProductionRequest{" +
                "producers=" + producers +
                ", topics=" + topics +
                ", events=" + events +
                ", partitions=" + partitions +
                '}';
    }
}
