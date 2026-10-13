package kaflow.broker;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Caller-owned immutable input. Absent and present-empty keys are distinct. */
public record AppendCommand(TopicId topic, OptionalInt partition,
                            Optional<ImmutableBytes> key, ImmutableBytes payload) {
    public AppendCommand {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
    }
}
