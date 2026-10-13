package kaflow.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static kaflow.storage.StorageException.Code.*;
import static kaflow.storage.StorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageIndexTest {
    @TempDir Path directory;

    private StorageOptions create() throws Exception {
        StorageOptions options = options(1024, 3);
        try (PartitionLog log = PartitionLog.open(directory, ID, options, CLOCK)) {
            for (int i = 0; i < 12; i++) log.append(Optional.empty(), bytes((byte) i));
        }
        return options;
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "short", "stale", "version", "position", "offset", "length", "checksum", "extra", "interval"})
    void derivedIndexDamageIsRebuiltFromValidatedFrames(String damage) throws Exception {
        StorageOptions options = create();
        Path path = directory.resolve(ZERO_INDEX);
        byte[] expected = Files.readAllBytes(path);
        byte[] logBefore = Files.readAllBytes(directory.resolve(ZERO_LOG));
        byte[] damaged = expected.clone();
        switch (damage) {
            case "missing" -> Files.delete(path);
            case "short" -> Files.write(path, new byte[]{1});
            case "stale" -> Files.write(path, Arrays.copyOf(damaged, damaged.length - 24));
            case "extra" -> Files.write(path, Arrays.copyOf(damaged, damaged.length + 24));
            default -> {
                ByteBuffer data = ByteBuffer.wrap(damaged);
                switch (damage) {
                    case "version" -> data.putShort(4, (short) 2);
                    case "interval" -> data.putInt(12, 1);
                    case "position" -> data.putLong(40, Long.MAX_VALUE);
                    case "offset" -> data.putLong(32, 2);
                    case "length" -> data.putInt(48, -1);
                    case "checksum" -> damaged[52] ^= 1;
                    default -> throw new AssertionError(damage);
                }
                if (damage.equals("position") || damage.equals("offset") || damage.equals("length")) {
                    // Even a checksummed entry must correspond to an actual validated frame.
                    byte[] entry = Arrays.copyOfRange(damaged, 32, 56);
                    crc(entry);
                    System.arraycopy(entry, 0, damaged, 32, 24);
                }
                Files.write(path, damaged);
            }
        }
        Files.write(directory.resolve(".index-rebuild.tmp"), new byte[]{99});
        try (PartitionLog log = PartitionLog.open(directory, ID, options, CLOCK)) {
            assertEquals(12, log.captureBoundary().endOffset());
            assertEquals(bytes((byte) 10), log.read(ID, 10, 1, 1, log.captureBoundary()).records().get(0).payload());
            assertArrayEquals(expected, Files.readAllBytes(path));
            assertArrayEquals(logBefore, Files.readAllBytes(directory.resolve(ZERO_LOG)));
            assertFalse(Files.exists(directory.resolve(".index-rebuild.tmp")));
        }
    }

    @Test
    void corruptLogNeverGetsHiddenByIndexRebuilding() throws Exception {
        StorageOptions options = create();
        Files.delete(directory.resolve(ZERO_INDEX));
        byte[] damaged = Files.readAllBytes(directory.resolve(ZERO_LOG));
        damaged[38 + 16] ^= 1; // Timestamp metadata, with unchanged CRC.
        Files.write(directory.resolve(ZERO_LOG), damaged);
        fails(CORRUPT, () -> PartitionLog.open(directory, ID, options, CLOCK));
        assertArrayEquals(damaged, Files.readAllBytes(directory.resolve(ZERO_LOG)));
        assertFalse(Files.exists(directory.resolve(ZERO_INDEX)));
    }

    @Test
    void everySegmentMustValidateBeforeAnyIndexIsRebuilt() throws Exception {
        StorageOptions options = options(74, 1);
        try (PartitionLog log = PartitionLog.open(directory, ID, options, CLOCK)) {
            log.append(Optional.empty(), EMPTY);
            log.append(Optional.empty(), EMPTY);
        }
        Files.delete(directory.resolve(ZERO_INDEX));
        Path second = directory.resolve("00000000000000000001.log");
        byte[] corrupt = Files.readAllBytes(second);
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(second, corrupt);
        fails(CORRUPT, () -> PartitionLog.open(directory, ID, options, CLOCK));
        assertFalse(Files.exists(directory.resolve(ZERO_INDEX)));
        assertArrayEquals(corrupt, Files.readAllBytes(second));
    }

    @Test
    void failedIndexRebuildReleasesOwnershipAndPreservesAuthoritativeLog() throws Exception {
        StorageOptions options = create();
        Files.delete(directory.resolve(ZERO_INDEX));
        byte[] before = Files.readAllBytes(directory.resolve(ZERO_LOG));
        ControlledIo io = new ControlledIo();
        io.targetSuffix = ".tmp";
        io.armed = true;
        io.failAfter = 5;
        assertSame(io.injected, assertThrows(java.io.IOException.class,
                () -> PartitionLog.open(directory, ID, options, CLOCK, io)));
        assertEquals(0, io.live);
        assertArrayEquals(before, Files.readAllBytes(directory.resolve(ZERO_LOG)));
        try (PartitionLog reopened = PartitionLog.open(directory, ID, options, CLOCK)) {
            assertEquals(12, reopened.captureBoundary().endOffset());
        }
    }

    @Test
    void cleanReopenDoesNotChangeAnyLogOrIndexBytes() throws Exception {
        StorageOptions options = create();
        Map<String, byte[]> before = contents();
        try (PartitionLog log = PartitionLog.open(directory, ID, options, CLOCK)) {
            assertEquals(12, log.read(ID, 0, 128, 100, log.captureBoundary()).records().size());
        }
        Map<String, byte[]> after = contents();
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), name));
    }

    @Test
    void changedSparseIntervalRebuildsAndLookupSkipsOnlyBoundedFrames() throws Exception {
        create();
        ControlledIo io = new ControlledIo();
        try (PartitionLog log = PartitionLog.open(directory, ID, options(1024, 4), CLOCK, io)) {
            long beforeBytes = io.readBytes;
            int beforeCalls = io.readCalls;
            var batch = log.read(ID, 7, 2, 2, log.captureBoundary());
            assertEquals(2, batch.records().size());
            assertEquals(7, batch.records().get(0).offset());
            assertEquals(9, batch.nextOffset());
            assertTrue(io.readBytes - beforeBytes <= 24 + (3 + 2) * 37);
            assertTrue(io.readCalls - beforeCalls <= 1 + (3 + 2) * 2);
            assertTrue(io.maxLive <= 4); // Plus the separate OS-lock channel = five.
        }
        assertEquals(0, io.live);
    }

    @Test
    void completeEmptyFinalSegmentCanBeReopenedAndAppended() throws Exception {
        fixture(directory, header("orders".getBytes(StandardCharsets.UTF_8), 2, 0), frame(0, Optional.empty(), new byte[0]));
        Files.write(directory.resolve("00000000000000000001.log"), header("orders".getBytes(StandardCharsets.UTF_8), 2, 1));
        try (PartitionLog log = open(directory)) {
            assertEquals(1, log.captureBoundary().endOffset());
            assertEquals(1, log.append(Optional.empty(), EMPTY).offset());
            assertEquals(2, log.read(ID, 0, 128, 0, log.captureBoundary()).records().size());
        }
    }

    private Map<String, byte[]> contents() throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) result.put(path.getFileName().toString(), Files.readAllBytes(path));
        }
        return result;
    }
}
