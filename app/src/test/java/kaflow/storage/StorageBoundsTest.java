package kaflow.storage;

import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import static kaflow.storage.StorageException.Code.*;
import static kaflow.storage.StorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageBoundsTest {
    @TempDir Path directory;

    @Test
    void immutableOptionsEnforceHardAllocationAndWorkBounds() {
        assertEquals(new StorageOptions(16777216, 1024, 16384, 1048576, 128, 128, 2097152, 8), StorageOptions.defaults());
        assertThrows(IllegalArgumentException.class, () -> options(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> options(68, 1));
        assertThrows(IllegalArgumentException.class, () -> options(100, 0));
        assertThrows(IllegalArgumentException.class, () -> options(100, 4097));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1025, 0, 0, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 0, 0, 0, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, -1, 0, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, 0, -1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, 0, 0, 1, 129, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, 0, 0, 1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, 0, 0, 1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, 0, 0, 1, 1, 2097153, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, 0, 0, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StorageOptions(100, 1, 0, 0, 1, 1, 0, 1025));
    }

    @Test
    void topicHeaderAndRecordMustFitBeforeDirectoryCreation() throws Exception {
        Path absent = directory.resolve("absent");
        TopicPartition longId = new TopicPartition(new TopicId("x".repeat(255)), 0);
        fails(INVALID_ARGUMENT, () -> PartitionLog.open(absent, longId, options(100, 1), CLOCK));
        assertFalse(Files.exists(absent));
    }

    @Test
    void reopenRejectsIncompatibleSegmentAndSegmentCountLimits() throws Exception {
        Path size = directory.resolve("size");
        try (PartitionLog log = open(size)) {
            log.append(Optional.empty(), EMPTY);
            log.append(Optional.empty(), EMPTY);
        }
        byte[] before = Files.readAllBytes(size.resolve(ZERO_LOG));
        fails(LIMIT, () -> PartitionLog.open(size, ID, options(74, 1), CLOCK));
        assertArrayEquals(before, Files.readAllBytes(size.resolve(ZERO_LOG)));
        Path count = directory.resolve("count");
        try (PartitionLog log = PartitionLog.open(count, ID, options(74, 1), CLOCK)) {
            log.append(Optional.empty(), EMPTY);
            log.append(Optional.empty(), EMPTY);
        }
        StorageOptions single = new StorageOptions(74, 1, 0, 0, 1, 1, 0, 8);
        fails(LIMIT, () -> PartitionLog.open(count, ID, single, CLOCK));
    }

    @Test
    void customReadAndPayloadLimitsAreCheckedBeforeIo() throws Exception {
        StorageOptions limits = new StorageOptions(512, 2, 1, 1, 1, 1, 1, 8);
        ControlledIo io = new ControlledIo();
        try (PartitionLog log = PartitionLog.open(directory, ID, limits, CLOCK, io)) {
            log.append(Optional.of(bytes((byte) 1)), bytes((byte) 2));
            int reads = io.readCalls;
            int writes = io.writeCalls;
            fails(INVALID_ARGUMENT, () -> log.read(ID, 0, 2, 1, log.captureBoundary()));
            fails(INVALID_ARGUMENT, () -> log.read(ID, 0, 1, 2, log.captureBoundary()));
            fails(INVALID_ARGUMENT, () -> log.append(Optional.of(bytes((byte) 1, (byte) 2)), EMPTY));
            fails(INVALID_ARGUMENT, () -> log.append(Optional.empty(), bytes((byte) 1, (byte) 2)));
            assertEquals(reads, io.readCalls);
            assertEquals(writes, io.writeCalls);
            fails(BUDGET_TOO_SMALL, () -> log.read(ID, 0, 1, 1, log.captureBoundary()));
            assertEquals(1, log.captureBoundary().endOffset());
        }
    }
}
