package kaflow.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static kaflow.storage.StorageException.Code.*;
import static kaflow.storage.StorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageFailureTest {
    @TempDir Path directory;

    @Test
    void shortTransfersAndIntermittentZeroesCompleteFromCorrectPositions() throws Exception {
        ControlledIo io = new ControlledIo();
        io.readChunk = 2;
        io.writeChunk = 3;
        io.alternatingZeroes = true;
        try (PartitionLog log = PartitionLog.open(directory, ID, options(512, 2), CLOCK, io)) {
            for (int i = 0; i < 15; i++) {
                assertEquals(i, log.append(Optional.of(bytes((byte) -1)), bytes((byte) i, (byte) -128)).offset());
            }
            var records = log.read(ID, 0, 128, 100, log.captureBoundary()).records();
            assertEquals(15, records.size());
            for (int i = 0; i < 15; i++) assertEquals(bytes((byte) i, (byte) -128), records.get(i).payload());
        }
        assertEquals(io.opened, io.closed);
        assertTrue(io.maxLive <= 4);
        try (PartitionLog reopened = PartitionLog.open(directory, ID, options(512, 2), CLOCK, io)) {
            assertEquals(15, reopened.captureBoundary().endOffset());
        }
        assertEquals(0, io.live);
    }

    @Test
    void zeroProgressWriteFailsBoundedlyWithoutAdvancingAndPreservesCause() throws Exception {
        ControlledIo io = new ControlledIo();
        try (PartitionLog log = PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io)) {
            io.zeroWrites = true;
            int before = io.writeCalls;
            StorageException original = fails(IO_STALLED, () -> log.append(Optional.empty(), EMPTY));
            assertEquals(8, io.writeCalls - before);
            assertEquals(0, log.captureBoundary().endOffset());
            assertEquals(PartitionLog.State.FAILED, log.state());
            assertSame(original, fails(FAILED, () -> log.append(Optional.empty(), EMPTY)).getCause());
            fails(FAILED, () -> log.read(ID, 0, 1, 0, log.captureBoundary()));
            fails(OWNERSHIP, () -> open(directory));
        }
        try (PartitionLog reopened = open(directory)) {
            assertEquals(0, reopened.captureBoundary().endOffset());
        }
    }

    @Test
    void zeroProgressReadsFailBoundedlyAndReleaseInitializationResources() throws Exception {
        try (PartitionLog log = open(directory)) { log.append(Optional.empty(), EMPTY); }
        ControlledIo io = new ControlledIo();
        io.zeroReads = true;
        fails(IO_STALLED, () -> PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io));
        assertEquals(8, io.readCalls);
        assertEquals(0, io.live);
        try (PartitionLog reopened = open(directory)) { assertEquals(1, reopened.captureBoundary().endOffset()); }
    }

    @Test
    void partialFrameFailureLatchesStateAndReopenDoesNotRepairTail() throws Exception {
        ControlledIo io = new ControlledIo();
        long size;
        try (PartitionLog log = PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io)) {
            log.append(Optional.empty(), EMPTY);
            size = Files.size(directory.resolve(ZERO_LOG));
            io.armed = true;
            io.failAfter = 5;
            IOException failure = assertThrows(IOException.class, () -> log.append(Optional.empty(), bytes((byte) 1)));
            assertSame(io.injected, failure);
            assertEquals(size + 5, Files.size(directory.resolve(ZERO_LOG)));
            assertEquals(1, log.captureBoundary().endOffset());
            assertSame(failure, fails(FAILED, () -> log.append(Optional.empty(), EMPTY)).getCause());
            fails(OWNERSHIP, () -> open(directory));
        }
        assertEquals(0, io.live);
        byte[] partial = Files.readAllBytes(directory.resolve(ZERO_LOG));
        fails(CORRUPT, () -> open(directory));
        assertArrayEquals(partial, Files.readAllBytes(directory.resolve(ZERO_LOG)));
    }

    @Test
    void indexWriteFailureLeavesCompletedFrameWithNoReceiptOrBoundaryAdvance() throws Exception {
        ControlledIo io = new ControlledIo();
        io.targetSuffix = ".idx";
        try (PartitionLog log = PartitionLog.open(directory, ID, options(1024, 1), CLOCK, io)) {
            io.armed = true;
            io.failAfter = 5;
            assertSame(io.injected, assertThrows(IOException.class, () -> log.append(Optional.empty(), bytes((byte) 9))));
            assertEquals(0, log.captureBoundary().endOffset());
            assertEquals(PartitionLog.State.FAILED, log.state());
        }
        // A structurally complete reopen is not proof that the failed write was acknowledged/durable.
        try (PartitionLog reopened = PartitionLog.open(directory, ID, options(1024, 1), CLOCK)) {
            assertEquals(1, reopened.captureBoundary().endOffset());
            assertEquals(bytes((byte) 9), reopened.read(ID, 0, 1, 1, reopened.captureBoundary()).records().get(0).payload());
        }
    }

    @Test
    void partialRotationHeaderFailsWithoutResumingOrOverwriting() throws Exception {
        ControlledIo io = new ControlledIo();
        try (PartitionLog log = PartitionLog.open(directory, ID, options(74, 1), CLOCK, io)) {
            log.append(Optional.empty(), EMPTY);
            io.armed = true;
            io.failAfter = 5;
            assertSame(io.injected, assertThrows(IOException.class, () -> log.append(Optional.empty(), EMPTY)));
            assertEquals(1, log.captureBoundary().endOffset());
            assertEquals(5, Files.size(directory.resolve("00000000000000000001.log")));
            fails(FAILED, () -> log.append(Optional.empty(), EMPTY));
        }
        fails(CORRUPT, () -> PartitionLog.open(directory, ID, options(74, 1), CLOCK));
        assertEquals(5, Files.size(directory.resolve("00000000000000000001.log")));
        assertEquals(0, io.live);
    }

    @Test
    void rotationIndexCreationFailureLeavesCompleteEmptyFinalSegment() throws Exception {
        ControlledIo io = new ControlledIo();
        io.targetSuffix = ".idx";
        try (PartitionLog log = PartitionLog.open(directory, ID, options(74, 1), CLOCK, io)) {
            log.append(Optional.empty(), EMPTY);
            io.armed = true;
            io.failCreate = true;
            assertSame(io.injected, assertThrows(IOException.class, () -> log.append(Optional.empty(), EMPTY)));
            assertEquals(1, log.captureBoundary().endOffset());
            assertEquals(PartitionLog.State.FAILED, log.state());
        }
        assertEquals(0, io.live);
        try (PartitionLog reopened = PartitionLog.open(directory, ID, options(74, 1), CLOCK)) {
            assertEquals(1, reopened.append(Optional.empty(), EMPTY).offset());
        }
    }

    @Test
    void createNewNeverOverwritesAnExistingRotationTarget() throws Exception {
        Path target = directory.resolve("00000000000000000001.log");
        try (PartitionLog log = PartitionLog.open(directory, ID, options(74, 1), CLOCK)) {
            log.append(Optional.empty(), EMPTY);
            Files.write(target, new byte[]{1, 2, 3});
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> log.append(Optional.empty(), EMPTY));
            assertEquals(1, log.captureBoundary().endOffset());
            assertEquals(PartitionLog.State.FAILED, log.state());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target));
        }
    }

    @Test
    void initializationFailureClosesChannelsAndSuppressesCleanupError() throws Exception {
        ControlledIo io = new ControlledIo();
        io.armed = true;
        io.failAfter = 5;
        io.failClose = true;
        IOException primary = assertThrows(IOException.class,
                () -> PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io));
        assertSame(io.injected, primary);
        assertEquals(1, primary.getSuppressed().length);
        assertSame(io.cleanup, primary.getSuppressed()[0]);
        assertEquals(0, io.live);
        // Ownership was released; the subsequent failure is malformed data, not an ownership conflict.
        fails(CORRUPT, () -> open(directory));
    }

    @Test
    void closeStillReleasesOtherResourcesAfterChannelCloseFailure() throws Exception {
        ControlledIo io = new ControlledIo();
        PartitionLog log = PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io);
        io.armed = true;
        io.failClose = true;
        assertSame(io.cleanup, assertThrows(IOException.class, log::close));
        log.close();
        assertEquals(PartitionLog.State.CLOSED, log.state());
        assertEquals(0, io.live);
        try (PartitionLog reopened = open(directory)) { assertEquals(0, reopened.captureBoundary().endOffset()); }
    }

    @Test
    void unsafeIndexPositionDuringReadFailsOwnerWithoutReadingOutsideBounds() throws Exception {
        try (PartitionLog log = open(directory)) {
            log.append(Optional.empty(), EMPTY);
            Path index = directory.resolve(ZERO_INDEX);
            byte[] data = Files.readAllBytes(index);
            byte[] entry = java.util.Arrays.copyOfRange(data, 32, 56);
            ByteBuffer.wrap(entry).putLong(8, Long.MAX_VALUE);
            crc(entry);
            System.arraycopy(entry, 0, data, 32, entry.length);
            Files.write(index, data);
            fails(CORRUPT, () -> log.read(ID, 0, 1, 0, log.captureBoundary()));
            assertEquals(PartitionLog.State.FAILED, log.state());
        }
    }

    @Test
    void budgetErrorRemainsPrimaryWhenReadCleanupAlsoFails() throws Exception {
        ControlledIo io = new ControlledIo();
        try (PartitionLog log = PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io)) {
            log.append(Optional.empty(), bytes((byte) 9));
            io.armed = true;
            io.failClose = true;
            StorageException budget = fails(BUDGET_TOO_SMALL, () -> log.read(ID, 0, 1, 0, log.captureBoundary()));
            assertEquals(1, budget.getSuppressed().length);
            assertSame(io.cleanup, budget.getSuppressed()[0]);
            assertEquals(PartitionLog.State.FAILED, log.state());
            assertSame(budget, fails(FAILED, () -> log.append(Optional.empty(), EMPTY)).getCause());
        }
        assertEquals(0, io.live);
    }

    @Test
    void truncatedIndexIsRejectedBeforeAttemptingAnOutOfFileRead() throws Exception {
        ControlledIo io = new ControlledIo();
        try (PartitionLog log = PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io)) {
            log.append(Optional.empty(), EMPTY);
            Files.write(directory.resolve(ZERO_INDEX), new byte[32]);
            int before = io.readCalls;
            fails(CORRUPT, () -> log.read(ID, 0, 1, 0, log.captureBoundary()));
            assertEquals(before, io.readCalls);
            assertEquals(PartitionLog.State.FAILED, log.state());
        }
    }

    @Test
    void liveReadStallFailsTheOwnerWithoutChangingCompletedBoundary() throws Exception {
        ControlledIo io = new ControlledIo();
        try (PartitionLog log = PartitionLog.open(directory, ID, StorageOptions.defaults(), CLOCK, io)) {
            log.append(Optional.empty(), EMPTY);
            io.zeroReads = true;
            int before = io.readCalls;
            fails(IO_STALLED, () -> log.read(ID, 0, 1, 0, log.captureBoundary()));
            assertEquals(8, io.readCalls - before);
            assertEquals(1, log.captureBoundary().endOffset());
            assertEquals(PartitionLog.State.FAILED, log.state());
        }
    }

    @Test
    void ioPositionOverflowIsRejectedBeforeAnyTransfer() throws Exception {
        ControlledIo io = new ControlledIo();
        try (var file = io.open(directory.resolve("scratch"), StorageIo.Mode.CREATE)) {
            fails(INVALID_ARGUMENT, () -> StorageIo.writeFully(file, ByteBuffer.allocate(2), Long.MAX_VALUE, 8));
            fails(INVALID_ARGUMENT, () -> StorageIo.readFully(file, ByteBuffer.allocate(2), -1, 8));
            assertEquals(0, io.writeCalls);
            assertEquals(0, io.readCalls);
        }
    }
}
