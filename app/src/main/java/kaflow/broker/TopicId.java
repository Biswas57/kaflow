package kaflow.broker;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exact identity: no trimming, case folding, or normalization. */
public record TopicId(String value) {
    public TopicId {
        Objects.requireNonNull(value, "topic");
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("topic must be nonblank and at most 255 UTF-8 bytes");
        }
    }
}
