package kaflow.broker;

/** Logical per-reference accounting, not a bound on caller memory or total JVM heap. */
public record BrokerLimits(int maxTopics, int maxPartitionsPerTopic, int maxTotalPartitions,
                           int maxKeyBytes, int maxPayloadBytes,
                           int maxAdmittedCalls, long maxAdmittedBytes,
                           long maxRetainedRecords, long maxRetainedBytes) {
    public BrokerLimits {
        if (maxTopics <= 0 || maxPartitionsPerTopic <= 0 || maxTotalPartitions <= 0
                || maxAdmittedCalls <= 0 || maxRetainedRecords <= 0
                || maxKeyBytes < 0 || maxPayloadBytes < 0
                || maxAdmittedBytes < 0 || maxRetainedBytes < 0) {
            throw new IllegalArgumentException("count limits must be positive; byte limits nonnegative");
        }
    }

    public static BrokerLimits defaults() {
        return new BrokerLimits(128, 64, 1024, 16 * 1024, 1024 * 1024,
                128, 16L * 1024 * 1024, 100_000, 64L * 1024 * 1024);
    }
}
