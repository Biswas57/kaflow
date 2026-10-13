package kaflow.storage;

import kaflow.broker.ImmutableBytes;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static kaflow.storage.StorageException.Code.*;
import static kaflow.storage.StorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class PartitionLogTest {
    @TempDir Path directory;

    @Test
    void literalFrameBytesPreserveBinaryPayloadEmptyAndAbsentKeys() throws Exception {
        // Independently calculated using Python struct and bitwise Castagnoli CRC,
        // cross-checked against the standard CRC32C("123456789") = e3069283 vector.
        String expected = "4b464c470001000000000026000000060000000200000000000000006f7264657273462010aa"
                + "0000002900010001000000000000000000000000075bcd15000000000000000500ff80c3282d1656a2"
                + "0000002400010000000000000000000100000000075bcd15ffffffff00000000a153ad55";
        try (PartitionLog log = open(directory)) {
            byte[] input = {0, -1, -128, -61, 40};
            ImmutableBytes payload = bytes(input);
            input[0] = 42;
            var first = log.append(Optional.of(EMPTY), payload);
            var second = log.append(Optional.empty(), EMPTY);
            assertEquals(ID, first.partition());
            assertEquals(0, first.offset());
            assertEquals(CLOCK.millis(), first.timestampMillis());
            assertEquals(1, first.completedWriteEnd());
            assertEquals(1, second.offset());
            assertEquals(2, second.completedWriteEnd());
            assertArrayEquals(HexFormat.of().parseHex(expected), Files.readAllBytes(directory.resolve(ZERO_LOG)));
            var batch = log.read(ID, 0, 128, 1024, log.captureBoundary());
            assertEquals(2, batch.records().size());
            assertEquals(2, batch.nextOffset());
            assertTrue(batch.records().get(0).key().isPresent());
            assertEquals(0, batch.records().get(0).key().orElseThrow().length());
            assertTrue(batch.records().get(1).key().isEmpty());
            assertEquals(payload, batch.records().get(0).payload());
            assertEquals(CLOCK.millis(), batch.records().get(0).timestampMillis());
            assertEquals(ID, batch.records().get(0).partition());
            batch.records().get(0).payload().toByteArray()[0] = 55;
            assertEquals(payload, batch.records().get(0).payload());
            assertThrows(UnsupportedOperationException.class, () -> batch.records().clear());
            log.close();
            assertEquals(payload, batch.records().get(0).payload());
        }
    }

    @Test
    void independentFixturesOpenAndEveryOffsetMatchesItsBytesAcrossSegments() throws Exception {
        fixture(directory, header("orders".getBytes(StandardCharsets.UTF_8), 2, 0),
                frame(0, Optional.of(new byte[]{-1, 0}), new byte[]{-128}),
                frame(1, Optional.empty(), new byte[0]));
        try (PartitionLog log = open(directory)) {
            var records = log.read(ID, 0, 128, 1024, log.captureBoundary()).records();
            assertEquals(bytes((byte) -1, (byte) 0), records.get(0).key().orElseThrow());
            assertEquals(bytes((byte) -128), records.get(0).payload());
            assertEquals(2, log.append(Optional.empty(), EMPTY).offset());
        }
    }

    @Test
    void exactSegmentLimitRotatesOnlyBeforeOverflowAndPreservesOrdering() throws Exception {
        StorageOptions options = options(38 + 2 * 37, 2);
        try (PartitionLog log = PartitionLog.open(directory, ID, options, CLOCK)) {
            for (int i = 0; i < 9; i++) {
                var receipt = log.append(Optional.of(EMPTY), bytes((byte) i));
                assertEquals(i, receipt.offset());
                assertEquals(i + 1, receipt.completedWriteEnd());
                if (i == 1) assertFalse(Files.exists(directory.resolve("00000000000000000002.log")));
            }
            assertEquals(112, Files.size(directory.resolve(ZERO_LOG)));
            try (var files = Files.list(directory)) {
                assertEquals(List.of("00000000000000000000.log", "00000000000000000002.log",
                        "00000000000000000004.log", "00000000000000000006.log", "00000000000000000008.log"),
                        files.filter(p -> p.toString().endsWith(".log")).map(p -> p.getFileName().toString()).sorted().toList());
            }
            var batch = log.read(ID, 1, 7, 7, log.captureBoundary());
            assertEquals(8, batch.nextOffset());
            for (int i = 0; i < 7; i++) {
                assertEquals(i + 1, batch.records().get(i).offset());
                assertEquals(bytes((byte) (i + 1)), batch.records().get(i).payload());
            }
        }
        try (PartitionLog reopened = PartitionLog.open(directory, ID, options, CLOCK)) {
            assertEquals(9, reopened.append(Optional.empty(), EMPTY).offset());
        }
    }

    @Test
    void oversizedFramesAndCapacityRejectionsDoNotModifyFilesOrOffsets() throws Exception {
        StorageOptions options = new StorageOptions(74, 1, 1, 10, 1, 128, 2097152, 8);
        try (PartitionLog log = PartitionLog.open(directory, ID, options, CLOCK)) {
            byte[] before = Files.readAllBytes(directory.resolve(ZERO_LOG));
            fails(LIMIT, () -> log.append(Optional.empty(), bytes((byte) 1)));
            assertArrayEquals(before, Files.readAllBytes(directory.resolve(ZERO_LOG)));
            fails(INVALID_ARGUMENT, () -> log.append(Optional.of(bytes((byte) 1, (byte) 2)), EMPTY));
            fails(INVALID_ARGUMENT, () -> log.append(Optional.empty(), bytes(new byte[11])));
            assertEquals(0, log.append(Optional.empty(), EMPTY).offset());
            byte[] full = Files.readAllBytes(directory.resolve(ZERO_LOG));
            fails(LIMIT, () -> log.append(Optional.empty(), EMPTY));
            assertArrayEquals(full, Files.readAllBytes(directory.resolve(ZERO_LOG)));
            assertEquals(1, log.captureBoundary().endOffset());
            assertEquals(PartitionLog.State.OPEN, log.state());
        }
        assertEquals(Long.MAX_VALUE, PartitionLog.nextOffset(Long.MAX_VALUE - 1));
        fails(LIMIT, () -> PartitionLog.nextOffset(Long.MAX_VALUE));
        fails(LIMIT, () -> PartitionLog.nextOffset(-1));
    }

    @Test
    void boundedReadsCaptureEndAndContinueWithoutSkippingOverBudgetRecords() throws Exception {
        try (PartitionLog log = open(directory)) {
            var empty = log.captureBoundary();
            assertTrue(log.read(ID, 0, 1, 0, empty).records().isEmpty());
            log.append(Optional.empty(), EMPTY);
            log.append(Optional.of(bytes((byte) 7)), bytes((byte) 1, (byte) 2));
            var boundary = log.captureBoundary();
            log.append(Optional.empty(), bytes((byte) 3));
            assertEquals(2, boundary.endOffset());
            assertEquals(ID, boundary.partition());
            var first = log.read(ID, 0, 128, 0, boundary);
            assertEquals(1, first.records().size());
            assertEquals(1, first.nextOffset());
            fails(BUDGET_TOO_SMALL, () -> log.read(ID, 1, 128, 2, boundary));
            var next = log.read(ID, first.nextOffset(), 128, 3, boundary);
            assertEquals(1, next.records().size());
            assertEquals(2, next.nextOffset());
            assertEquals(1, next.records().get(0).offset());
            assertTrue(log.read(ID, 2, 128, 3, boundary).records().isEmpty());
            assertEquals(Long.MAX_VALUE, log.read(ID, Long.MAX_VALUE, 1, 0, boundary).nextOffset());
            assertEquals(3, log.read(ID, 0, 128, 10, log.captureBoundary()).records().size());
            assertEquals(0, log.read(ID, 0, 1, 10, empty).nextOffset());
            assertEquals(PartitionLog.State.OPEN, log.state());
        }
    }

    @Test
    void countLimitBoundsZeroByteReadsAndInvalidRequestsStayHealthy() throws Exception {
        try (PartitionLog log = open(directory.resolve("first")); PartitionLog other = open(directory.resolve("other"))) {
            for (int i = 0; i < 130; i++) log.append(Optional.empty(), EMPTY);
            var boundary = log.captureBoundary();
            assertEquals(128, log.read(ID, 0, 128, 0, boundary).records().size());
            assertEquals(2, log.read(ID, 128, 128, 0, boundary).records().size());
            fails(INVALID_ARGUMENT, () -> log.read(ID, -1, 1, 1, boundary));
            fails(INVALID_ARGUMENT, () -> log.read(ID, 0, 0, 1, boundary));
            fails(INVALID_ARGUMENT, () -> log.read(ID, 0, 129, 1, boundary));
            fails(INVALID_ARGUMENT, () -> log.read(ID, 0, 1, -1, boundary));
            fails(INVALID_ARGUMENT, () -> log.read(ID, 0, 1, 2097153, boundary));
            fails(INVALID_ARGUMENT, () -> log.read(ID, 0, 1, 1, other.captureBoundary()));
            fails(WRONG_IDENTITY, () -> log.read(new TopicPartition(new TopicId("other"), 2), 0, 1, 1, boundary));
            assertEquals(PartitionLog.State.OPEN, log.state());
        }
    }

    @Test
    void closeIsIdempotentAndIndependentDirectoriesCanReuseIdentifiers() throws Exception {
        Path left = directory.resolve("left");
        Path right = directory.resolve("right");
        try (PartitionLog first = open(left); PartitionLog second = open(right)) {
            first.append(Optional.empty(), bytes((byte) 1));
            second.append(Optional.empty(), bytes((byte) 2));
            var boundary = first.captureBoundary();
            first.close();
            first.close();
            fails(CLOSED, () -> first.append(Optional.empty(), EMPTY));
            fails(CLOSED, first::captureBoundary);
            fails(CLOSED, () -> first.read(ID, 0, 1, 1, boundary));
            assertEquals(PartitionLog.State.CLOSED, first.state());
            assertEquals(bytes((byte) 2), second.read(ID, 0, 1, 1, second.captureBoundary()).records().get(0).payload());
        }
    }
}
