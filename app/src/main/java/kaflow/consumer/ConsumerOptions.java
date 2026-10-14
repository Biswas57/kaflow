package kaflow.consumer;

/** Broker-owned work bounds, excluding caller and transport allocations. */
public record ConsumerOptions(int readWorkers, int maxReads, long maxReadCharge, int maxProgressCalls, long maxProgressCharge) {
    public ConsumerOptions {
        if (readWorkers < 1 || readWorkers > 4 || maxReads < 1 || maxReads > 128
                || maxReadCharge < 0 || maxReadCharge > 256L * 1024 * 1024 || maxProgressCalls < 1 || maxProgressCalls > 256
                || maxProgressCharge < 0 || maxProgressCharge > 8L * 1024 * 1024) throw new IllegalArgumentException("consumer bounds");
    }
    public static ConsumerOptions defaults() { return new ConsumerOptions(2, 16, 64L * 1024 * 1024, 64, 1024 * 1024); }
}
