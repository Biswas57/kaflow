package tributary.api.dto;

import java.util.List;
import tributary.core.tributaryObject.Topic;

public record TopicView(String id, List<PartitionView> partitions) {
    public static TopicView from(Topic<?> topic) {
        return new TopicView(
                topic.getId(),
                topic.listPartitions().stream()
                        .map(PartitionView::from)
                        .toList());
    }
}
