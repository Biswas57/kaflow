package tributary.api;

import org.json.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class TributaryAPI {

    private TributaryController controller = new TributaryController();

    public static void main(String[] args) {
        SpringApplication.run(TributaryAPI.class, args);
    }

    @PostMapping("/topics")
    public JSONObject createTopic(@RequestParam String id, @RequestParam String type) {
        
        try {
            controller.createTopic(id, type);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Topic '" + id + "' created successfully.");
            return response;
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject();
            error.put("success", true);
            error.put("error", e.getMessage());
            return error;
        }
    }

    @PostMapping("/partitions")
    public JSONObject createPartition(@RequestParam String topicId, @RequestParam String partitionId) {
        try {
            controller.createPartition(topicId, partitionId);
            JSONObject response = new JSONObject();
            response.put("message", "Partition '" + partitionId + "' created in topic '" + topicId + "'.");
            return response;
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage());
            return error;
        }
    }

    @PostMapping("/consumerGroups")
    public JSONObject createConsumerGroup(@RequestParam String groupId,
                                          @RequestParam String topicId,
                                          @RequestParam String rebalancing) {
        try {
            controller.createConsumerGroup(groupId, topicId, rebalancing);
            JSONObject response = new JSONObject();
            response.put("message", "Consumer group '" + groupId + "' created for topic '" + topicId + "'.");
            return response;
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage());
            return error;
        }
    }

    @PostMapping("/consumers")
    public JSONObject createConsumer(@RequestParam String groupId, @RequestParam String consumerId) {
        try {
            controller.createConsumer(groupId, consumerId);
            JSONObject response = new JSONObject();
            response.put("message", "Consumer '" + consumerId + "' created in group '" + groupId + "'.");
            return response;
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage());
            return error;
        }
    }

    @PostMapping("/producers")
    public JSONObject createProducer(@RequestParam String producerId,
                                     @RequestParam String topicId,
                                     @RequestParam String allocation) {
        try {
            controller.createProducer(producerId, topicId, allocation);
            JSONObject response = new JSONObject();
            response.put("message", "Producer '" + producerId + "' created for topic '" + topicId + "'.");
            return response;
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage());
            return error;
        }
    }

    @PostMapping("/events")
    public JSONObject createEvent(@RequestParam String producerId,
                                  @RequestParam String topicId,
                                  @RequestParam String eventId,
                                  @RequestParam(required = false) String partitionId) {
        try {
            controller.createEvent(producerId, topicId, eventId, partitionId);
            JSONObject response = new JSONObject();
            response.put("message", "Event '" + eventId + "' created for topic '" + topicId + "'.");
            return response;
        } catch (IOException e) {
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage());
            return error;
        }
    }

    @GetMapping("/topics/{id}")
    public JSONObject showTopic(@PathVariable String id) {
        JSONObject topicJson = controller.showTopic(id);
        if (topicJson == null) {
            JSONObject error = new JSONObject();
            error.put("error", "Topic '" + id + "' not found.");
            return error;
        }
        return topicJson;
    }

    @GetMapping("/consumerGroups/{groupId}")
    public JSONObject showGroup(@PathVariable String groupId) {
        JSONObject groupJson = controller.showGroup(groupId);
        if (groupJson == null) {
            JSONObject error = new JSONObject();
            error.put("error", "Consumer group '" + groupId + "' not found.");
            return error;
        }
        return groupJson;
    }

    @GetMapping("/consume")
    public JSONObject consumeEvents(@RequestParam String consumerId,
                                    @RequestParam String partitionId,
                                    @RequestParam int numberOfEvents) {
        JSONObject result = controller.consumeEvents(consumerId, partitionId, numberOfEvents);
        if (result == null) {
            JSONObject error = new JSONObject();
            error.put("error", "Failed to consume events.");
            return error;
        }
        return result;
    }

    @PutMapping("/rebalancing")
    public JSONObject updateRebalancing(@RequestParam String groupId,
                                        @RequestParam String rebalancing) {
        controller.updateRebalancing(groupId, rebalancing);
        JSONObject response = new JSONObject();
        response.put("message", "Rebalancing updated for consumer group '" + groupId + "'.");
        return response;
    }

    @PutMapping("/offset")
    public JSONObject updateOffset(@RequestParam String consumerId,
                                   @RequestParam String partitionId,
                                   @RequestParam int offset) {
        controller.updatePartitionOffset(consumerId, partitionId, offset);
        JSONObject response = new JSONObject();
        response.put("message", "Offset updated for consumer '" + consumerId + "' on partition '" + partitionId + "'.");
        return response;
    }

    @PutMapping("/admin/group")
    public JSONObject updateAdminGroup(@RequestParam String newGroupId,
                                       @RequestParam(required = false) String oldGroupId,
                                       @RequestParam(required = false) String password) {
        controller.updateConsumerGroupAdmin(newGroupId, oldGroupId, password);
        JSONObject response = new JSONObject();
        response.put("message", "Admin role updated to consumer group '" + newGroupId + "'.");
        return response;
    }

    @PutMapping("/admin/producer")
    public JSONObject updateAdminProducer(@RequestParam String newProdId,
                                          @RequestParam(required = false) String oldProdId,
                                          @RequestParam(required = false) String password) {
        controller.updateProducerAdmin(newProdId, oldProdId, password);
        JSONObject response = new JSONObject();
        response.put("message", "Admin role updated to producer '" + newProdId + "'.");
        return response;
    }

    @PostMapping("/parallel/produce")
    public JSONObject parallelProduce(@RequestBody String[] parts) {
        controller.parallelProduce(parts);
        JSONObject response = new JSONObject();
        response.put("message", "Parallel production executed.");
        return response;
    }

    @PostMapping("/parallel/consume")
    public JSONObject parallelConsume(@RequestBody String[] parts) {
        controller.parallelConsume(parts);
        JSONObject response = new JSONObject();
        response.put("message", "Parallel consumption executed.");
        return response;
    }
}
