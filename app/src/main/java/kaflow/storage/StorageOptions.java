package kaflow.storage;

/** Immutable logical/work bounds, not a total JVM heap or filesystem durability promise. */
public record StorageOptions(long segmentBytes, int maxSegments, int maxKeyBytes, int maxPayloadBytes,
                             int indexInterval, int maxReadRecords, long maxReadBytes, int zeroProgressLimit) {
    public StorageOptions {
        if (segmentBytes < 69 || segmentBytes > 128L * 1024 * 1024
                || maxSegments < 1 || maxSegments > 1024
                || maxKeyBytes < 0 || maxPayloadBytes < 0
                || (long) maxKeyBytes + maxPayloadBytes > 8L * 1024 * 1024
                || indexInterval < 1 || indexInterval > 4096
                || maxReadRecords < 1 || maxReadRecords > 128
                || maxReadBytes < 0 || maxReadBytes > 2L * 1024 * 1024
                || zeroProgressLimit < 1 || zeroProgressLimit > 1024) {
            throw new IllegalArgumentException("storage options outside supported bounds");
        }
    }

    public static StorageOptions defaults() {
        return new StorageOptions(16L * 1024 * 1024, 1024, 16 * 1024, 1024 * 1024,
                128, 128, 2L * 1024 * 1024, 8);
    }
}
