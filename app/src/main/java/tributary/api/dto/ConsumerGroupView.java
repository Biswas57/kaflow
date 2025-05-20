package tributary.api.dto;

import java.util.List;
import tributary.core.tributaryObject.ConsumerGroup;

public record ConsumerGroupView(
        String id,
        List<ConsumerView> consumers) {
    public static ConsumerGroupView from(ConsumerGroup<?> group) {
        return new ConsumerGroupView(
                group.getId(),
                group.listConsumers().stream()
                        .map(ConsumerView::from)
                        .toList());
    }
}
