package kaflow.consumer;

/** Positive-wait metadata/delivery bounds; active read admission is separate. */
public record WaitOptions(int maxRequests, int maxPerSession, int maxPerPartition,
                          long maxMetadataBytes, long maxWaitMillis, int batch) {
    public static final long METADATA_CHARGE = 2048;
    public WaitOptions {
        if (maxRequests < 1 || maxRequests > 1024 || maxPerSession < 1 || maxPerSession > maxRequests
                || maxPerPartition < 1 || maxPerPartition > maxRequests
                || maxMetadataBytes < 0 || maxMetadataBytes > 2L * 1024 * 1024
                || maxWaitMillis < 1 || maxWaitMillis > 120000 || batch < 1 || batch > 32)
            throw new IllegalArgumentException("wait bounds");
    }
    public static WaitOptions defaults() { return new WaitOptions(256, 16, 64, 512 * 1024, 30000, 8); }
}
