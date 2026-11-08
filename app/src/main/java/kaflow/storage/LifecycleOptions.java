package kaflow.storage;

import kaflow.groups.GroupOptions;

/** Opt-in retention target and independent hard file-length quotas. Zero target disables retention. */
public record LifecycleOptions(long retentionBytes, long maxStorageBytes, long maxPartitionBytes) {
    private static final long CEILING = 16L << 40;
    public LifecycleOptions {
        if (retentionBytes < 0 || maxStorageBytes < 1 || maxStorageBytes > CEILING
                || maxPartitionBytes < 1 || maxPartitionBytes > maxStorageBytes
                || retentionBytes > maxPartitionBytes) throw new IllegalArgumentException("lifecycle limits");
    }
    public static LifecycleOptions defaults() { return new LifecycleOptions(0, 8L << 30, 1L << 30); }
    long metadataBudget(DurableOptions options, GroupOptions groups) {
        var s = options.storage();
        long index = LogFormat.INDEX_HEADER + Math.multiplyExact(LogFormat.INDEX_ENTRY,
                1 + s.segmentBytes() / LogFormat.FRAME_OVERHEAD / s.indexInterval());
        // All installed authorities, one replacement per kind, upgrade overlap and index scratch.
        return Math.addExact(17 + 2L * groups.maxCatalogBytes() + 2L * (options.maxPartitions() + options.maxPartitionsPerTopic()) * 347
                + 2L * (groups.maxGroups() + 1) * (52L + 8L * options.maxPartitionsPerTopic()), index);
    }
    void validate(DurableOptions options, GroupOptions groups) {
        long frame = LogFormat.FRAME_OVERHEAD + (long) options.storage().maxKeyBytes() + options.storage().maxPayloadBytes();
        // Configurations may intentionally limit frames further by segment capacity.
        frame = Math.min(frame, options.storage().segmentBytes() - 33);
        long rotation = frame + 2L * (287 + LogFormat.INDEX_HEADER + LogFormat.INDEX_ENTRY);
        if (maxPartitionBytes < rotation || maxStorageBytes - metadataBudget(options, groups) < rotation)
            throw new IllegalArgumentException("quota cannot accommodate record/rotation and maintenance headroom");
    }
}
