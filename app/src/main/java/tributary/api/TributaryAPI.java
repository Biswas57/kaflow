package tributary.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import tributary.api.dto.*;

@RestController
@RequestMapping("/api")
class TributaryAPI {

    private final TributaryController controller = new TributaryController();

    // Topic endpoints

    @PostMapping("/topics")
    public ResponseEntity<Void> createTopic(@RequestBody NewTopic req,
            UriComponentsBuilder uri) {
        controller.createTopic(req.id(), req.type());
        var location = uri.path("/api/topics/{id}").buildAndExpand(req.id()).toUri();
        return ResponseEntity.created(location).build(); // 201 + Location
    }

    @DeleteMapping("/topics/{id}")
    public ResponseEntity<Void> deleteTopic(@PathVariable String id) {
        controller.deleteTopic(id);
        return ResponseEntity.noContent().build(); // 204
    }

    @GetMapping("/topics/{id}")
    public TopicView showTopic(@PathVariable String id) {
        return TopicView.from(controller.showTopic(id));
    }

    // Topic partition endpoints
    @PostMapping("/topics/{topicId}/partitions")
    public ResponseEntity<Void> createPartition(@PathVariable String topicId,
            @RequestBody NewPartition req,
            UriComponentsBuilder uri) {
        controller.createPartition(topicId, req.partitionId());
        var location = uri.path("/api/topics/{tid}/partitions/{pid}")
                .buildAndExpand(topicId, req.partitionId()).toUri();
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/topics/{topicId}/partitions/{partitionId}")
    public ResponseEntity<Void> deletePartition(@PathVariable String topicId,
            @PathVariable String partitionId) {
        controller.deletePartition(topicId, partitionId);
        return ResponseEntity.noContent().build();
    }

    // Consumer group endpoints
    @PostMapping("/consumerGroups")
    public ResponseEntity<Void> createConsumerGroup(@RequestBody NewConsumerGroup req,
            UriComponentsBuilder uri) {
        controller.createConsumerGroup(req.id(), req.topicId(), req.rebalancing());
        var location = uri.path("/api/consumerGroups/{id}").buildAndExpand(req.id()).toUri();
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/consumerGroups/{id}")
    public ResponseEntity<Void> deleteConsumerGroup(@PathVariable String id) {
        controller.deleteConsumerGroup(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/consumerGroups/{id}")
    public ConsumerGroupView showGroup(@PathVariable String id) {
        return ConsumerGroupView.from(controller.showGroup(id));
    }

    // Consumer endpoints
    @PostMapping("/consumerGroups/{groupId}/consumers")
    public ResponseEntity<Void> createConsumer(@PathVariable String groupId,
            @RequestBody NewConsumer req,
            UriComponentsBuilder uri) {
        controller.createConsumer(groupId, req.id());
        var location = uri.path("/api/consumerGroups/{gid}/consumers/{cid}")
                .buildAndExpand(groupId, req.id()).toUri();
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/consumerGroups/{groupId}/consumers/{consumerId}")
    public ResponseEntity<Void> deleteConsumer(@PathVariable String groupId,
            @PathVariable String consumerId) {
        controller.deleteConsumer(groupId, consumerId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/consumerGroups/{groupId}/rebalance")
    public ResponseEntity<Void> updateRebalance(@PathVariable String groupId,
            @RequestBody RebalanceRequest req) {
        controller.updateRebalancing(groupId, req.strategy());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/consumerGroups/{groupId}/consumers/{consumerId}/offset")
    public ResponseEntity<Void> updateOffset(@PathVariable String groupId,
            @PathVariable String consumerId,
            @RequestBody OffsetRequest req) {
        controller.updatePartitionOffset(groupId, consumerId, req.partitionId(), req.offset());
        return ResponseEntity.noContent().build();
    }

    // Producer endpoints
    @PostMapping("/producers")
    public ResponseEntity<Void> createProducer(@RequestBody NewProducer req,
            UriComponentsBuilder uri) {
        controller.createProducer(req.id(), req.topicId(), req.allocation());
        var location = uri.path("/api/producers/{id}").buildAndExpand(req.id()).toUri();
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/producers/{id}")
    public ResponseEntity<Void> deleteProducer(@PathVariable String id) {
        controller.deleteProducer(id);
        return ResponseEntity.noContent().build();
    }
}
