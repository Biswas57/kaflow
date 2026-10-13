package kaflow.broker;

import org.junit.jupiter.api.Test;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static kaflow.broker.BrokerException.Code.*;
import static kaflow.broker.BrokerTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BrokerContractsTest {
    @Test
    void exactTopicIdentityAndUtf8LengthBounds() {
        assertEquals(" orders ", new TopicId(" orders ").value());
        assertNotEquals(TOPIC, new TopicId("Orders"));
        assertNotEquals(TOPIC, new TopicId(" orders "));
        assertThrows(IllegalArgumentException.class, () -> new TopicId(" \t\n"));
        assertThrows(IllegalArgumentException.class, () -> new TopicId("x".repeat(256)));
        assertEquals(255, new TopicId("x".repeat(255)).value().length());
        assertDoesNotThrow(() -> new TopicId("é".repeat(127) + "x"));
        assertThrows(IllegalArgumentException.class, () -> new TopicId("é".repeat(128)));
        assertThrows(NullPointerException.class, () -> new TopicId(null));
        assertThrows(IllegalArgumentException.class, () -> new TopicPartition(TOPIC, -1));
        assertNotEquals(new TopicPartition(TOPIC, 0), new TopicPartition(new TopicId("other"), 0));
    }

    @Test
    void bytesAreCopiedAtPublicBoundaryAndEqualByContent() {
        byte[] input = {0, (byte) 255, (byte) 128, (byte) 195, 40};
        ImmutableBytes value = bytes(input);
        ImmutableBytes equal = bytes(input);
        input[0] = 99;
        value.toByteArray()[1] = 42;
        assertEquals(equal, value);
        assertEquals(equal.hashCode(), value.hashCode());
        assertEquals(5, value.length());
        assertArrayEquals(new byte[]{0, (byte) 255, (byte) 128, (byte) 195, 40}, value.toByteArray());
        assertThrows(ReadOnlyBufferException.class, () -> value.asReadOnlyBuffer().put(0, (byte) 1));
        assertThrows(ReadOnlyBufferException.class, () -> value.asReadOnlyBuffer().array());
        value.asReadOnlyBuffer().get();
        assertEquals(0, value.asReadOnlyBuffer().position());
        assertNotEquals(value, EMPTY);
    }

    @Test
    void binaryAndEmptyRecordsPreserveEveryByteAndKeyPresence() {
        try (InMemoryBroker broker = new InMemoryBroker(BrokerLimits.defaults(), CLOCK)) {
            broker.createTopic(TOPIC, 1);
            for (byte[] input : List.of(new byte[0], new byte[]{0, -1, -128, -61, 40}, allBytes())) {
                byte[] expected = input.clone();
                AppendCommand command = new AppendCommand(TOPIC, OptionalInt.empty(),
                        Optional.of(bytes(input)), bytes(input));
                java.util.Arrays.fill(input, (byte) 7);
                AppendResult result = broker.append(command);
                assertEquals(CLOCK.millis(), result.timestampMillis());
                matches(broker, command, result);
                StoredRecord record = broker.snapshot(result.partition(), result.offset(), 1).get(0);
                if (expected.length > 0) {
                    command.payload().toByteArray()[0] = 42;
                    record.payload().toByteArray()[0] = 43;
                    record.key().orElseThrow().toByteArray()[0] = 44;
                }
                assertArrayEquals(expected, command.payload().toByteArray());
                assertArrayEquals(expected, record.payload().toByteArray());
                assertArrayEquals(expected, record.key().orElseThrow().toByteArray());
                matches(broker, command, result);
            }
            AppendResult absent = broker.append(command(EMPTY));
            assertTrue(broker.snapshot(absent.partition(), absent.offset(), 1).get(0).key().isEmpty());
            assertTrue(broker.snapshot(absent.partition(), 0, 1).get(0).key().isPresent());
        }
    }

    private static byte[] allBytes() {
        byte[] values = new byte[256];
        for (int i = 0; i < values.length; i++) values[i] = (byte) i;
        return values;
    }

    @Test
    void independentCoresAndQualifiedPartitionsNeverShareState() {
        try (InMemoryBroker first = new InMemoryBroker(); InMemoryBroker second = new InMemoryBroker()) {
            first.createTopic(TOPIC, 2);
            second.createTopic(TOPIC, 1);
            TopicId other = new TopicId("other");
            first.createTopic(other, 1);
            assertEquals(0, first.append(explicit(0, bytes((byte) 1))).offset());
            assertEquals(0, second.append(command(bytes((byte) 2))).offset());
            AppendCommand otherCommand = new AppendCommand(other, OptionalInt.of(0), Optional.empty(), EMPTY);
            AppendResult otherResult = first.append(otherCommand);
            assertEquals(0, otherResult.offset());
            assertEquals(new TopicPartition(other, 0), otherResult.partition());
            assertEquals(bytes((byte) 1), first.snapshot(new TopicPartition(TOPIC, 0), 0, 1).get(0).payload());
            assertEquals(bytes((byte) 2), second.snapshot(new TopicPartition(TOPIC, 0), 0, 1).get(0).payload());
            first.close();
            assertEquals(1, second.append(command(EMPTY)).offset());
        }
    }

    @Test
    void literalSha256VectorsAndExplicitPrecedenceLeaveRoundRobinAlone() {
        // Independent Python hashlib + OpenSSL first-eight-byte vectors, never a production helper.
        List<byte[]> keys = List.of(new byte[0], "order-42".getBytes(StandardCharsets.UTF_8),
                HexFormat.of().parseHex("00ff80c328"), HexFormat.of().parseHex("ff"));
        int[] expected = {1, 5, 0, 2};
        try (InMemoryBroker broker = new InMemoryBroker()) {
            broker.createTopic(TOPIC, 7);
            for (int i = 0; i < keys.size(); i++) {
                AppendCommand keyed = new AppendCommand(TOPIC, OptionalInt.empty(), Optional.of(bytes(keys.get(i))), EMPTY);
                AppendResult receipt = broker.append(keyed);
                assertEquals(expected[i], receipt.partition().partition());
                matches(broker, keyed, receipt);
                AppendCommand explicit = new AppendCommand(TOPIC, OptionalInt.of(6), keyed.key(), EMPTY);
                assertEquals(6, broker.append(explicit).partition().partition());
                fails(INVALID_ARGUMENT, () -> broker.append(new AppendCommand(TOPIC, OptionalInt.of(7), keyed.key(), EMPTY)));
            }
            for (int i = 0; i < 15; i++) {
                assertEquals(i % 7, broker.append(command(EMPTY)).partition().partition());
            }
        }
    }

    @Test
    void roundRobinIsPerTopicAndAbsentDiffersFromPresentEmptyKey() {
        try (InMemoryBroker broker = new InMemoryBroker()) {
            TopicId other = new TopicId("other");
            broker.createTopic(TOPIC, 7);
            broker.createTopic(other, 2);
            AppendCommand otherCommand = new AppendCommand(other, OptionalInt.empty(), Optional.empty(), EMPTY);
            assertEquals(0, broker.append(command(EMPTY)).partition().partition());
            assertEquals(0, broker.append(otherCommand).partition().partition());
            AppendCommand emptyKey = new AppendCommand(TOPIC, OptionalInt.empty(), Optional.of(EMPTY), EMPTY);
            AppendResult keyed = broker.append(emptyKey);
            assertEquals(1, keyed.partition().partition());
            matches(broker, emptyKey, keyed);
            assertEquals(1, broker.append(command(EMPTY)).partition().partition());
            assertEquals(1, broker.append(otherCommand).partition().partition());
            assertEquals(0, broker.append(otherCommand).partition().partition());
        }
    }

    @Test
    void synchronousSamePartitionOrderAndRejectionLeaveNoGaps() {
        try (InMemoryBroker broker = new InMemoryBroker()) {
            broker.createTopic(TOPIC, 2);
            for (int i = 0; i < 50; i++) {
                fails(INVALID_ARGUMENT, () -> broker.append(explicit(-1, EMPTY)));
                fails(NOT_FOUND, () -> broker.append(new AppendCommand(new TopicId("missing"),
                        OptionalInt.empty(), Optional.empty(), EMPTY)));
                AppendCommand command = explicit(1, bytes((byte) i));
                AppendResult result = broker.append(command);
                assertEquals(i, result.offset());
                matches(broker, command, result);
            }
            List<StoredRecord> records = broker.snapshot(new TopicPartition(TOPIC, 1), 0, 128);
            assertEquals(50, records.size());
            for (int i = 0; i < 50; i++) assertEquals(bytes((byte) i), records.get(i).payload());
            assertEquals(0, broker.append(command(EMPTY)).partition().partition());
            assertEquals(0, broker.accounting().admittedCalls());
            assertEquals(0, broker.accounting().admittedBytes());
        }
    }

    @Test
    void offsetIncrementRejectsOverflowBeforeMutation() {
        assertEquals(1, InMemoryBroker.nextOffset(0));
        assertEquals(Long.MAX_VALUE, InMemoryBroker.nextOffset(Long.MAX_VALUE - 1));
        fails(RESOURCE_EXHAUSTED, () -> InMemoryBroker.nextOffset(Long.MAX_VALUE));
    }
}
