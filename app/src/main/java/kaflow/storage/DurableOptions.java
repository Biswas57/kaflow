package kaflow.storage;

import java.time.Duration;
import java.util.Objects;

/** Limits for the durable runtime; caller/transport memory is outside admission. */
public record DurableOptions(StorageOptions storage, int maxTopics, int maxPartitionsPerTopic,
                             int maxPartitions, int maxCalls, long maxBytes, Duration watchdog) {
    public DurableOptions {
        Objects.requireNonNull(storage);
        Objects.requireNonNull(watchdog);
        if (maxTopics < 1 || maxTopics > 128 || maxPartitionsPerTopic < 1 || maxPartitionsPerTopic > 64
                || maxPartitions < 1 || maxPartitions > 128 || maxCalls < 1 || maxCalls > 1024
                || maxBytes < 0 || maxBytes > 64L * 1024 * 1024 || watchdog.isNegative()
                || watchdog.isZero() || watchdog.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("durable options outside supported bounds");
        }
    }

    public static DurableOptions defaults() {
        return new DurableOptions(StorageOptions.defaults(), 32, 16, 64, 128,
                16L * 1024 * 1024, Duration.ofSeconds(30));
    }
}
