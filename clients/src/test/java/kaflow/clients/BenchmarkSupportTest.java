package kaflow.clients;

import io.grpc.Status;
import java.nio.ByteBuffer;
import java.util.UUID;
import kaflow.v1.*;
import org.junit.jupiter.api.Test;
import static kaflow.clients.BenchmarkSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkSupportTest {
    @Test void fixedSizePayloadContainsIndependentRunAndSequenceIdentity() {
        var run = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] first = payload(run, 42, (byte) 2, 256), second = payload(run, 43, (byte) 2, 256);
        assertEquals(256, first.length); assertFalse(java.util.Arrays.equals(first, second));
        var header = ByteBuffer.wrap(first);
        assertEquals(0x4b464239, header.getInt()); assertEquals(0x1111111122223333L, header.getLong());
        assertEquals(0x4444555555555555L, header.getLong()); assertEquals(42, header.getLong()); assertEquals(2, header.get());
        assertThrows(IllegalArgumentException.class, () -> payload(run, 0, (byte) 0, 28));
    }
    @Test void ledgerReservesInFlightCapacityAndPreservesAbsentVersusEmptyKeys() {
        var ledger = new Ledger(2, 4096); var run = UUID.randomUUID();
        var absent = ledger.reserve(run, (byte) 2, "bench", 0, 256, -1);
        var empty = ledger.reserve(run, (byte) 2, "bench", 0, 256, 0);
        assertFalse(absent.request.hasKey()); assertTrue(empty.request.hasKey());
        assertNull(absent.receipt); assertThrows(CapReached.class, () -> ledger.reserve(run, (byte) 2, "bench", 0, 256, 16));
        assertThrows(CapReached.class, () -> new Ledger(10, 1).reserve(run, (byte) 2, "bench", 0, 256, 16));
        assertEquals(2, ledger.snapshot().length);
    }
    @Test void cutoffThroughputExcludesDrainAndUnresolvedWorkHasNoInventedLatency() {
        var window = new Window(1000, 1000);
        assertTrue(window.issue(1100)); window.complete(1100, 1800, Status.Code.OK, 2, 50);
        assertTrue(window.issue(1900)); assertTrue(window.issue(1950)); assertFalse(window.issue(2000));
        window.atCutoff(); window.complete(1900, 2300, Status.Code.OK, 4, 100); window.unresolved();
        var result = window.json();
        assertEquals(1, result.getJSONObject("inWindow").getLong("OK"));
        assertEquals(1, result.getJSONObject("duringDrain").getLong("OK"));
        assertEquals(2, result.getLong("inFlightAtCutoff")); assertEquals(1, result.getLong("unresolvedAtDrainDeadline"));
        assertEquals(1000000, result.getDouble("successfulRpcsPerSecond"));
        assertEquals(2000000, result.getDouble("recordsPerSecond"));
        assertEquals(1, result.getJSONObject("allTerminalLatency").getLong("count"));
        assertEquals(1, result.getJSONObject("drainAllTerminalLatency").getLong("count"));
    }
    @Test void histogramUsesBoundedBucketsWithDocumentedUpperBounds() {
        var histogram = new Histogram(); histogram.add(1000);
        assertEquals(1024, histogram.json().getLong("p99UpperNanos"));
        assertEquals(1000, histogram.json().getLong("minNanos"));
        for (int i = 0; i < 10000; i++) histogram.add(1000);
        assertEquals(10001, histogram.json().getLong("count")); assertEquals(1024, histogram.json().getLong("p50UpperNanos"));
    }
    @Test void verificationRequiresExactTimestampKeyPresencePayloadAndReceiptIdentity() {
        var entry = new Ledger(1, 4096).reserve(UUID.randomUUID(), (byte) 2, "bench", 0, 256, -1);
        var partition = TopicPartition.newBuilder().setTopic("bench").setPartition(0).build();
        entry.receipt = AppendResponse.newBuilder().setPartition(partition).setOffset(7).setTimestampMillis(11).build();
        var record = kaflow.v1.Record.newBuilder().setPartition(partition).setOffset(7).setTimestampMillis(11).setPayload(entry.request.getPayload()).build();
        match(entry, record);
        assertThrows(IllegalStateException.class, () -> match(entry, record.toBuilder().setTimestampMillis(12).build()));
        assertThrows(IllegalStateException.class, () -> match(entry, record.toBuilder().setKey(com.google.protobuf.ByteString.EMPTY).build()));
        assertThrows(IllegalStateException.class, () -> match(entry, record.toBuilder().setOffset(8).build()));
    }
}
