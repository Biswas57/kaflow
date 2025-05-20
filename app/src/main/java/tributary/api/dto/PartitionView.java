package tributary.api.dto;

import tributary.core.tributaryObject.Message;
import tributary.core.tributaryObject.Partition;

public record PartitionView(String id, java.util.List<String> messages) {

    public static PartitionView from(Partition<?> p) {
        return new PartitionView(
                p.getId(),
                p.listMessages().stream()
                        .map(Message::getId) // or whatever accessor you have
                        .toList());
    }
}
