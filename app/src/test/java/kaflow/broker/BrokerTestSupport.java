package kaflow.broker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

final class BrokerTestSupport {
    static final TopicId TOPIC = new TopicId("orders");
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(123456789), ZoneOffset.UTC);
    static final ImmutableBytes EMPTY = bytes(new byte[0]);

    static ImmutableBytes bytes(byte... value) {
        return ImmutableBytes.copyOf(value);
    }

    static AppendCommand command(ImmutableBytes payload) {
        return new AppendCommand(TOPIC, OptionalInt.empty(), Optional.empty(), payload);
    }

    static AppendCommand explicit(int partition, ImmutableBytes payload) {
        return new AppendCommand(TOPIC, OptionalInt.of(partition), Optional.empty(), payload);
    }

    static void fails(BrokerException.Code code, Executable call) {
        assertEquals(code, assertThrows(BrokerException.class, call).code());
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static void matches(InMemoryBroker broker, AppendCommand command, AppendResult result) {
        StoredRecord record = broker.snapshot(result.partition(), result.offset(), 1).get(0);
        assertEquals(result.partition(), record.partition());
        assertEquals(result.offset(), record.offset());
        assertEquals(result.timestampMillis(), record.timestampMillis());
        assertEquals(command.key(), record.key());
        assertEquals(command.payload(), record.payload());
    }
}
