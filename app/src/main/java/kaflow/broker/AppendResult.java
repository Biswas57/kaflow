package kaflow.broker;

import java.util.Objects;

/** Proof of volatile insertion only; never a durable or network acknowledgement. */
public record AppendResult(TopicPartition partition, long offset, long timestampMillis) {
    public AppendResult {
        Objects.requireNonNull(partition, "partition");
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset");
        }
    }
}
