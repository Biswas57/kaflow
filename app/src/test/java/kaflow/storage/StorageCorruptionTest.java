package kaflow.storage;

import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static kaflow.storage.StorageException.Code.*;
import static kaflow.storage.StorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageCorruptionTest {
    @TempDir Path directory;

    static Stream<Arguments> badFrames() {
        return Stream.of(
                Arguments.of("negative frame length", 0, -1, CORRUPT),
                Arguments.of("huge frame length", 0, Integer.MAX_VALUE, CORRUPT),
                Arguments.of("unsupported version", 4, 0x00020000, UNSUPPORTED_VERSION),
                Arguments.of("reserved flags", 4, 0x00010002, CORRUPT),
                Arguments.of("present key with absent length", 4, 0x00010001, CORRUPT),
                Arguments.of("absent key with empty length", 24, 0, CORRUPT),
                Arguments.of("huge key length", 24, Integer.MAX_VALUE, CORRUPT),
                Arguments.of("invalid negative key", 24, -2, CORRUPT),
                Arguments.of("negative payload length", 28, -1, CORRUPT),
                Arguments.of("huge payload length", 28, Integer.MAX_VALUE, CORRUPT),
                Arguments.of("offset gap", 12, 1, CORRUPT),
                Arguments.of("negative offset", 8, -1, CORRUPT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFrames")
    void rejectsInvalidFramesBeforeVariableAllocation(String label, int position, int value,
                                                       StorageException.Code code) throws Exception {
        byte[] record = frame(0, Optional.empty(), new byte[]{0, -1});
        ByteBuffer.wrap(record).putInt(position, value);
        crc(record); // Metadata itself is invalid even with a correct checksum.
        fixture(directory, header("orders".getBytes(StandardCharsets.UTF_8), 2, 0), record);
        byte[] before = Files.readAllBytes(directory.resolve(ZERO_LOG));
        fails(code, () -> open(directory));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve(ZERO_LOG)));
        assertFalse(Files.exists(directory.resolve(ZERO_INDEX)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16, 24, 32, 37})
    void checksumCoversMetadataAndOpaqueData(int position) throws Exception {
        byte[] record = frame(0, Optional.of(new byte[]{1, 2}), new byte[]{3, 4});
        record[position] ^= 1;
        fixture(directory, header("orders".getBytes(StandardCharsets.UTF_8), 2, 0), record);
        fails(CORRUPT, () -> open(directory));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 31, 35, 36})
    void incompleteFramesFailClosedWithoutTruncation(int keep) throws Exception {
        byte[] record = frame(0, Optional.empty(), new byte[]{-1});
        fixture(directory, header("orders".getBytes(StandardCharsets.UTF_8), 2, 0), Arrays.copyOf(record, keep));
        byte[] before = Files.readAllBytes(directory.resolve(ZERO_LOG));
        fails(CORRUPT, () -> open(directory));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve(ZERO_LOG)));
    }

    static Stream<Arguments> badHeaders() {
        return Stream.of(
                Arguments.of("magic", 0, 0, CORRUPT),
                Arguments.of("version", 4, 0x00020000, UNSUPPORTED_VERSION),
                Arguments.of("flags", 4, 0x00010001, CORRUPT),
                Arguments.of("length", 8, Integer.MAX_VALUE, CORRUPT),
                Arguments.of("topic length", 12, Integer.MAX_VALUE, CORRUPT),
                Arguments.of("negative topic length", 12, -1, CORRUPT),
                Arguments.of("negative partition", 16, -1, CORRUPT),
                Arguments.of("wrong partition", 16, 3, WRONG_IDENTITY),
                Arguments.of("wrong base", 24, 1, CORRUPT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badHeaders")
    void rejectsInvalidHeaders(String label, int position, int value, StorageException.Code code) throws Exception {
        byte[] header = header("orders".getBytes(StandardCharsets.UTF_8), 2, 0);
        ByteBuffer.wrap(header).putInt(position, value);
        fixture(directory, crc(header));
        fails(code, () -> open(directory));
    }

    @Test
    void headerChecksumUtf8IdentityAndOffsetOverflowAreValidated() throws Exception {
        Path checksum = directory.resolve("checksum");
        byte[] header = header("orders".getBytes(StandardCharsets.UTF_8), 2, 0);
        header[28] ^= 1;
        fixture(checksum, header);
        fails(CORRUPT, () -> open(checksum));
        Path wrong = directory.resolve("wrong");
        fixture(wrong, header("Orders".getBytes(StandardCharsets.UTF_8), 2, 0));
        fails(WRONG_IDENTITY, () -> open(wrong));
        Path utf8 = directory.resolve("utf8");
        fixture(utf8, header(new byte[]{-1}, 2, 0));
        fails(CORRUPT, () -> open(utf8));
        Path overflow = directory.resolve("overflow");
        fixture(overflow, header("orders".getBytes(StandardCharsets.UTF_8), 2, 0),
                frame(Long.MAX_VALUE, Optional.empty(), new byte[0]));
        fails(CORRUPT, () -> open(overflow));
    }

    @Test
    void losslessTopicEncodingAndPathLikeNamesCannotEscapeTheDirectory() throws Exception {
        Path invalid = directory.resolve("invalid");
        TopicPartition surrogate = new TopicPartition(new TopicId("\ud800"), 0);
        fails(INVALID_ARGUMENT, () -> PartitionLog.open(invalid, surrogate, StorageOptions.defaults(), CLOCK));
        assertFalse(Files.exists(invalid));
        TopicPartition traversal = new TopicPartition(new TopicId("../../elsewhere/雪"), 0);
        Path data = directory.resolve("data");
        try (PartitionLog log = PartitionLog.open(data, traversal, StorageOptions.defaults(), CLOCK)) {
            log.append(Optional.empty(), EMPTY);
            assertEquals(traversal, log.read(traversal, 0, 1, 0, log.captureBoundary()).records().get(0).partition());
            try (var entries = Files.list(data)) {
                assertEquals(3, entries.count());
            }
        }
        fails(WRONG_IDENTITY, () -> PartitionLog.open(data,
                new TopicPartition(new TopicId("elsewhere/雪"), 0), StorageOptions.defaults(), CLOCK));
    }

    @Test
    void segmentGapsMalformedNamesAndUnsafeEntriesFailClosed() throws Exception {
        Path gap = directory.resolve("gap");
        fixture(gap, header("orders".getBytes(StandardCharsets.UTF_8), 2, 0), frame(0, Optional.empty(), new byte[0]));
        Files.write(gap.resolve("00000000000000000002.log"), header("orders".getBytes(StandardCharsets.UTF_8), 2, 2));
        fails(CORRUPT, () -> open(gap));
        Path overflow = directory.resolve("name-overflow");
        Files.createDirectory(overflow);
        Files.write(overflow.resolve("99999999999999999999.log"), new byte[0]);
        fails(CORRUPT, () -> open(overflow));
        Path orphan = directory.resolve("orphan");
        Files.createDirectory(orphan);
        Files.write(orphan.resolve(ZERO_INDEX), new byte[0]);
        fails(CORRUPT, () -> open(orphan));
        Path unsafe = directory.resolve("unsafe");
        Files.createDirectory(unsafe);
        Files.createSymbolicLink(unsafe.resolve(ZERO_LOG), gap.resolve(ZERO_LOG));
        fails(CORRUPT, () -> open(unsafe));
        Path truncated = directory.resolve("truncated");
        fixture(truncated, new byte[31]);
        fails(CORRUPT, () -> open(truncated));
    }
}
