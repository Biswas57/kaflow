package kaflow.broker;

import java.util.Objects;

public record TopicPartition(TopicId topic, int partition) {
    public TopicPartition {
        Objects.requireNonNull(topic, "topic");
        if (partition < 0) {
            throw new IllegalArgumentException("negative partition");
        }
    }
}
