package kaflow.broker;

import java.util.Objects;
import java.util.Optional;

/** Immutable record sharing only immutable key/payload values with its command. */
public record StoredRecord(TopicPartition partition, long offset, long timestampMillis,
                           Optional<ImmutableBytes> key, ImmutableBytes payload) {
    public StoredRecord {
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset");
        }
    }
}
