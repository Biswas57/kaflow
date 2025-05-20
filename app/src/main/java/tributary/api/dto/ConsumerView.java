package tributary.api.dto;

import java.util.List;

import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.Partition;

public record ConsumerView(String id, List<String> partitions) {

    public static ConsumerView from(Consumer<?> c) {
        return new ConsumerView(
                c.getId(),
                c.listAssignedPartitions().stream()
                        .map(Partition::getId)
                        .toList());
    }
}
