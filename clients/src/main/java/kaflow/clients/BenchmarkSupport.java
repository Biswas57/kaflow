package kaflow.clients;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.v1.*;
import org.json.JSONObject;

/** Bounded measurement state. No sample list, per-record logging or broker/storage access. */
final class BenchmarkSupport {
    static final int HEADER = 29;
    static byte[] payload(UUID run, long sequence, byte phase, int bytes) {
        if (bytes < HEADER || bytes > 1048576) throw new IllegalArgumentException("payload must fit 29-byte identity and be <= 1 MiB");
        byte[] data = new byte[bytes];
        ByteBuffer.wrap(data).putInt(0x4b464239).putLong(run.getMostSignificantBits()).putLong(run.getLeastSignificantBits()).putLong(sequence).put(phase);
        for (int i = HEADER; i < bytes; i++) data[i] = (byte) (sequence + i);
        return data;
    }
    static long sequence(ByteString data, UUID run) {
        var b = data.asReadOnlyByteBuffer();
        if (b.remaining() < HEADER || b.getInt() != 0x4b464239 || b.getLong() != run.getMostSignificantBits()
                || b.getLong() != run.getLeastSignificantBits()) throw new IllegalStateException("verification: unknown run identity");
        return b.getLong();
    }
    static final class Histogram {
        private final long[] buckets = new long[64 * 16];
        private long count, total, min = Long.MAX_VALUE, max;
        synchronized void add(long nanos) {
            nanos = Math.max(1, nanos); int exponent = 63 - Long.numberOfLeadingZeros(nanos);
            int fraction = Math.min(15, (int) ((Math.scalb((double) nanos, -exponent) - 1) * 16));
            buckets[exponent * 16 + fraction]++; count++; total = Math.addExact(total, nanos);
            min = Math.min(min, nanos); max = Math.max(max, nanos);
        }
        private long percentile(double p) {
            if (count == 0) return 0;
            long rank = (long) Math.ceil(count * p), n = 0;
            for (int i = 0; i < buckets.length; i++) {
                n += buckets[i];
                if (n >= rank) return (long) Math.ceil(Math.scalb(1 + ((i % 16) + 1) / 16.0, i / 16));
            }
            return Long.MAX_VALUE;
        }
        synchronized JSONObject json() {
            return new JSONObject().put("count", count).put("minNanos", count == 0 ? 0 : min).put("maxNanos", max)
                    .put("meanNanos", count == 0 ? 0 : (double) total / count)
                    .put("p50UpperNanos", percentile(.5)).put("p95UpperNanos", percentile(.95)).put("p99UpperNanos", percentile(.99))
                    .put("method", "1024 fixed log2/subdivision-16 bins; upper bounds, <=6.25% relative plus 1ns rounding");
        }
    }
    static final class Window {
        final long start;
        final AtomicLong cutoff;
        final long[] inside = new long[Status.Code.values().length], drained = new long[inside.length];
        final Histogram successes = new Histogram(), all = new Histogram(), drainSuccesses = new Histogram(), drainAll = new Histogram(), append = new Histogram();
        final Histogram delivery = new Histogram(), drainedDelivery = new Histogram();
        long issued, active, cutoffActive, records, bytes, setupFailures, setupCanceled, unresolved;
        boolean truncated;
        Window(long start, long duration) { this.start = start; cutoff = new AtomicLong(Math.addExact(start, duration)); }
        synchronized boolean issue(long now) { if (now >= cutoff.get()) return false; issued++; active++; return true; }
        synchronized void complete(long dispatch, long finished, Status.Code code, int records, long bytes) {
            active--;
            if (finished < cutoff.get()) {
                inside[code.ordinal()]++; all.add(finished - dispatch);
                if (code == Status.Code.OK) { successes.add(finished - dispatch); this.records += records; this.bytes += bytes; }
            } else {
                drained[code.ordinal()]++; drainAll.add(finished - dispatch);
                if (code == Status.Code.OK) drainSuccesses.add(finished - dispatch);
            }
        }
        synchronized void atCutoff() { cutoffActive = active; }
        synchronized void abort(long now) { truncated = true; cutoff.accumulateAndGet(now, Math::min); }
        synchronized void setupFailed() { setupFailures++; }
        synchronized void setupCanceled() { setupCanceled++; }
        synchronized void unresolved() { unresolved = active; }
        synchronized void delivered(long dispatch, long completed) {
            (completed < cutoff.get() ? delivery : drainedDelivery).add(completed - dispatch);
        }
        synchronized JSONObject json() {
            cutoffActive = issued - java.util.Arrays.stream(inside).sum();
            double seconds = Math.max(1, cutoff.get() - start) / 1e9;
            var in = new JSONObject(); var drain = new JSONObject();
            for (var status : Status.Code.values()) { in.put(status.name(), inside[status.ordinal()]); drain.put(status.name(), drained[status.ordinal()]); }
            return new JSONObject().put("seconds", seconds).put("issued", issued).put("inFlightAtCutoff", cutoffActive)
                    .put("inWindow", in).put("duringDrain", drain).put("unresolvedAtDrainDeadline", unresolved)
                    .put("successfulRpcsPerSecond", inside[Status.Code.OK.ordinal()] / seconds).put("offeredRpcsPerSecond", issued / seconds)
                    .put("recordsPerSecond", records / seconds).put("bytesPerSecond", bytes / seconds)
                    .put("successLatency", successes.json()).put("allTerminalLatency", all.json())
                    .put("drainSuccessLatency", drainSuccesses.json()).put("drainAllTerminalLatency", drainAll.json())
                    .put("appendLatency", append.json()).put("parkedDeliveryLatency", delivery.json()).put("drainParkedDeliveryLatency", drainedDelivery.json())
                    .put("setupFailures", setupFailures).put("setupCanceledAtCutoff", setupCanceled)
                    .put("truncated", truncated);
        }
    }
    static final class Entry {
        final long sequence; final byte phase; final AppendRequest request;
        volatile AppendResponse receipt;
        volatile kaflow.v1.Record delivered;
        volatile Status.Code outcome;
        volatile boolean issued;
        Entry(long sequence, byte phase, AppendRequest request) { this.sequence = sequence; this.phase = phase; this.request = request; }
    }
    static final class Ledger {
        private final Entry[] entries;
        private final long maxBytes;
        private int size;
        private long charged;
        Ledger(int capacity, long maxBytes) {
            if (capacity < 1 || capacity > 100000 || maxBytes < 1 || maxBytes > 256L * 1024 * 1024) throw new IllegalArgumentException("ledger bounds");
            entries = new Entry[capacity]; this.maxBytes = maxBytes;
        }
        synchronized Entry reserve(UUID run, byte phase, String topic, int partition, int payloadBytes, int keyBytes) {
            long charge = 512L + 2L * (payloadBytes + Math.max(0, keyBytes));
            if (size == entries.length || charge > maxBytes - charged) throw new CapReached();
            long sequence = size;
            var request = AppendRequest.newBuilder().setTopic(topic).setPartition(partition)
                    .setPayload(ByteString.copyFrom(payload(run, sequence, phase, payloadBytes)));
            if (keyBytes >= 0) {
                byte[] key = new byte[keyBytes]; for (int i = 0; i < key.length; i++) key[i] = (byte) (sequence + i);
                request.setKey(ByteString.copyFrom(key));
            }
            var result = new Entry(sequence, phase, request.build()); entries[size++] = result; charged += charge; return result;
        }
        synchronized Entry[] snapshot() { return java.util.Arrays.copyOf(entries, size); }
        synchronized long chargedBytes() { return charged; }
    }
    static final class CapReached extends RuntimeException { CapReached() { super("receipt count/byte cap"); } }
    static void match(Entry expected, kaflow.v1.Record actual) {
        var receipt = expected.receipt;
        if (receipt == null || !receipt.getPartition().equals(actual.getPartition()) || receipt.getOffset() != actual.getOffset()
                || receipt.getTimestampMillis() != actual.getTimestampMillis() || expected.request.hasKey() != actual.hasKey()
                || !expected.request.getKey().equals(actual.getKey()) || !expected.request.getPayload().equals(actual.getPayload()))
            throw new IllegalStateException("verification: receipt/record mismatch");
    }
}
