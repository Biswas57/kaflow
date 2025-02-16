package tributary.api;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import tributary.core.parameterDataStructures.ParallelEventRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
@RestController
@RequestMapping("/api")
public class TributaryAPI {

    private TributaryController controller = new TributaryController();

    /**
     * The main entry point of the application.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        SpringApplication.run(TributaryAPI.class, args);
    }

    /**
     * Creates a new topic in the Tributary Cluster.
     *
     * @param id   The identifier for the new topic.
     * @param type The type of events that the topic will handle (e.g., "integer" or
     *             "string").
     * @return A ResponseEntity containing a JSON object with a success or error
     *         message.
     */
    @PostMapping("/topics")
    public ResponseEntity<JSONObject> createTopic(@RequestParam String id, @RequestParam String type) {
        JSONObject response = new JSONObject();
        try {
            controller.createTopic(id, type);
            response.put("message", "Topic '" + id + "' created successfully.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Creates a new partition in the specified topic.
     *
     * @param topicId     The identifier of the topic.
     * @param partitionId The identifier for the new partition.
     * @return A ResponseEntity containing a JSON object with a success or error
     *         message.
     */
    @PostMapping("/partitions")
    public ResponseEntity<JSONObject> createPartition(@RequestParam String topicId, @RequestParam String partitionId) {
        JSONObject response = new JSONObject();
        try {
            controller.createPartition(topicId, partitionId);
            response.put("message", "Partition '" + partitionId + "' created in topic '" + topicId + "'.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Creates a new consumer group for a given topic with a specified rebalancing
     * strategy.
     *
     * @param groupId     The identifier for the new consumer group.
     * @param topicId     The topic the consumer group is subscribed to.
     * @param rebalancing The rebalancing strategy (e.g., "range" or "roundrobin").
     * @return A ResponseEntity containing a JSON object with a success or error
     *         message.
     */
    @PostMapping("/consumerGroups")
    public ResponseEntity<JSONObject> createConsumerGroup(@RequestParam String groupId,
            @RequestParam String topicId,
            @RequestParam String rebalancing) {
        JSONObject response = new JSONObject();
        try {
            controller.createConsumerGroup(groupId, topicId, rebalancing);
            response.put("message", "Consumer group '" + groupId + "' created for topic '" + topicId + "'.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Creates a new consumer within a specified consumer group.
     *
     * @param groupId    The identifier of the consumer group.
     * @param consumerId The identifier for the new consumer.
     * @return A ResponseEntity containing a JSON object with a success or error
     *         message.
     */
    @PostMapping("/consumers")
    public ResponseEntity<JSONObject> createConsumer(@RequestParam String groupId, @RequestParam String consumerId) {
        JSONObject response = new JSONObject();
        try {
            controller.createConsumer(groupId, consumerId);
            response.put("message", "Consumer '" + consumerId + "' created in group '" + groupId + "'.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Creates a new producer for a given topic with a specified partition
     * allocation method.
     *
     * @param producerId The identifier for the producer.
     * @param topicId    The topic the producer will publish to.
     * @param allocation The partition allocation method ("random" or "manual").
     * @return A ResponseEntity containing a JSON object with a success or error
     *         message.
     */
    @PostMapping("/producers")
    public ResponseEntity<JSONObject> createProducer(@RequestParam String producerId,
            @RequestParam String topicId,
            @RequestParam String allocation) {
        JSONObject response = new JSONObject();
        try {
            controller.createProducer(producerId, topicId, allocation);
            response.put("message", "Producer '" + producerId + "' created for topic '" + topicId + "'.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Creates an event from a specified producer to a given topic and partition.
     *
     * @param producerId  The identifier of the producer.
     * @param topicId     The topic to publish the event to.
     * @param eventId     The event identifier (could be a filename or unique ID).
     * @param partitionId (Optional) The partition identifier.
     * @return A ResponseEntity containing a JSON object with a success or error
     *         message.
     */
    @PostMapping("/events")
    public ResponseEntity<JSONObject> createEvent(@RequestParam String producerId,
            @RequestParam String topicId,
            @RequestParam JSONObject event,
            @RequestParam(required = false) String partitionId) {
        JSONObject response = new JSONObject();
        try {
            controller.createEvent(producerId, topicId, event, partitionId);
            response.put("message", "Event '" + event.getString("eventId") + "' created for topic '" + topicId + "'.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        } catch (RuntimeException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Retrieves and returns a JSON representation of a specific topic.
     *
     * @param id The identifier of the topic.
     * @return A ResponseEntity containing the topic details as a JSON object or an
     *         error message.
     */
    @GetMapping("/topics/{id}")
    public ResponseEntity<JSONObject> showTopic(@PathVariable String id) {
        JSONObject topicJson = controller.showTopic(id);
        if (topicJson == null) {
            JSONObject error = new JSONObject();
            error.put("error", "Topic '" + id + "' not found.");
            return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(topicJson, HttpStatus.OK);
    }

    /**
     * Retrieves and returns a JSON representation of a specified consumer group,
     * including its consumers and partition assignments.
     *
     * @param groupId The identifier of the consumer group.
     * @return A ResponseEntity containing the consumer group details as a JSON
     *         object or an error message.
     */
    @GetMapping("/consumerGroups/{groupId}")
    public ResponseEntity<JSONObject> showGroup(@PathVariable String groupId) {
        JSONObject groupJson = controller.showGroup(groupId);
        if (groupJson == null) {
            JSONObject error = new JSONObject();
            error.put("error", "Consumer group '" + groupId + "' not found.");
            return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(groupJson, HttpStatus.OK);
    }

    /**
     * Consumes a specified number of events from a given partition using a specific
     * consumer.
     *
     * @param consumerId     The identifier of the consumer.
     * @param partitionId    The identifier of the partition.
     * @param numberOfEvents The number of events to consume.
     * @return A ResponseEntity containing the result of the consumption operation
     *         as a JSON object.
     */
    @GetMapping("/consume")
    public ResponseEntity<JSONObject> consumeEvents(@RequestParam String consumerId,
            @RequestParam String partitionId,
            @RequestParam int numberOfEvents) {
        JSONObject result = controller.consumeEvents(consumerId, partitionId, numberOfEvents);
        if (result == null) {
            JSONObject error = new JSONObject();
            error.put("error", "Failed to consume events.");
            return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * Updates the rebalancing strategy for a specified consumer group.
     *
     * @param groupId     The identifier of the consumer group.
     * @param rebalancing The new rebalancing strategy (e.g., "range" or
     *                    "roundrobin").
     * @return A ResponseEntity containing a JSON object with a success message.
     */
    @PutMapping("/rebalancing")
    public ResponseEntity<JSONObject> updateRebalancing(@RequestParam String groupId,
            @RequestParam String rebalancing) {
        controller.updateRebalancing(groupId, rebalancing);
        JSONObject response = new JSONObject();
        response.put("message", "Rebalancing updated for consumer group '" + groupId + "'.");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Updates the partition offset for a consumer to facilitate message replay or
     * backtracking.
     *
     * @param consumerId  The identifier of the consumer.
     * @param partitionId The identifier of the partition.
     * @param offset      The new offset value.
     * @return A ResponseEntity containing a JSON object with a success message.
     */
    @PutMapping("/offset")
    public ResponseEntity<JSONObject> updateOffset(@RequestParam String consumerId,
            @RequestParam String partitionId,
            @RequestParam int offset) {
        controller.updatePartitionOffset(consumerId, partitionId, offset);
        JSONObject response = new JSONObject();
        response.put("message", "Offset updated for consumer '" + consumerId + "' on partition '" + partitionId + "'.");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Transfers the admin role for a consumer group to a new group.
     *
     * @param newGroupId The identifier of the new admin consumer group.
     * @param oldGroupId (Optional) The identifier of the old admin consumer group.
     * @param password   (Optional) The password for verification.
     * @return A ResponseEntity containing a JSON object with a success message.
     */
    @PutMapping("/admin/group")
    public ResponseEntity<JSONObject> updateAdminGroup(@RequestParam String newGroupId,
            @RequestParam(required = false) String oldGroupId,
            @RequestParam(required = false) String password) {
        controller.updateConsumerGroupAdmin(newGroupId, oldGroupId, password);
        JSONObject response = new JSONObject();
        response.put("message", "Admin role updated to consumer group '" + newGroupId + "'.");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Transfers the admin role for a producer to a new producer.
     *
     * @param newProdId The identifier of the new admin producer.
     * @param oldProdId (Optional) The identifier of the old admin producer.
     * @param password  (Optional) The password for verification.
     * @return A ResponseEntity containing a JSON object with a success message.
     */
    @PutMapping("/admin/producer")
    public ResponseEntity<JSONObject> updateAdminProducer(@RequestParam String newProdId,
            @RequestParam(required = false) String oldProdId,
            @RequestParam(required = false) String password) {
        controller.updateProducerAdmin(newProdId, oldProdId, password);
        JSONObject response = new JSONObject();
        response.put("message", "Admin role updated to producer '" + newProdId + "'.");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Executes parallel event production using the specified commands.
     *
     * @param commands A whitespace-delimited string of commands for parallel
     *                 production.
     * @return A ResponseEntity containing a JSON object with a success message.
     */
    @PostMapping("/parallel/produce")
    public ResponseEntity<JSONObject> parallelProduce(@RequestBody String[] producerIds,
            @RequestBody String[] topicIds,
            @RequestBody JSONArray events,
            @RequestBody String[] partitionIds) {
        JSONObject response = new JSONObject();
        ParallelEventRequest requestParams = new ParallelEventRequest(Arrays.asList(producerIds),
                Arrays.asList(topicIds),
                events, Arrays.asList(partitionIds));
        try {
            controller.parallelProduce(requestParams);
            response.put("message", "Parallel production executed.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        } catch (RuntimeException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Executes parallel event consumption using the specified commands.
     *
     * @param commands A whitespace-delimited string of commands for parallel
     *                 consumption.
     * @return A ResponseEntity containing a JSON object with a success message.
     */
    @PostMapping("/parallel/consume")
    public ResponseEntity<JSONObject> parallelConsume(@RequestBody String[] consumerIds,
            @RequestParam String[] partitionIds,
            @RequestParam int[] numEvents) {
        JSONObject response = new JSONObject();

        List<Integer> numEventslist = new ArrayList<>();
        for (int num : numEvents) {
            numEventslist.add(num);
        }
        ParallelEventRequest requestParams = new ParallelEventRequest(Arrays.asList(consumerIds),
                Arrays.asList(partitionIds), numEventslist);
        try {
            controller.parallelConsume(requestParams);
            response.put("message", "Parallel production executed.");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
    }
}
