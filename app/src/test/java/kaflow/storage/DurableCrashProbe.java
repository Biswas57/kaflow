package kaflow.storage;

import kaflow.broker.ImmutableBytes;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** Separate JVM, halts without close at an actual persistence boundary. No JUnit runtime dependency. */
public final class DurableCrashProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); String failpoint = args[1];
        DurableOptions options = new DurableOptions(new StorageOptions(75,16,16,16,2,128,2097152,8),4,8,16,16,1000,java.time.Duration.ofSeconds(30));
        boolean[] armed = {false};
        Persistence disk = new Persistence() { @Override void event(String point) { if (armed[0] && point.equals(failpoint)) Runtime.getRuntime().halt(77); } };
        var topic = new TopicId("orders"); var part = new TopicPartition(topic,0);
        try (var store = DurableStore.open(root, options, Clock.fixed(Instant.ofEpochMilli(123456789), ZoneOffset.UTC), disk)) {
            store.createTopic(topic,1);
            var ack = store.append(part,Optional.empty(),ImmutableBytes.copyOf(new byte[]{42}),true);
            // Retain a receipt witness before terminating this writer process.
            try (var file = FileChannel.open(Path.of(args[2]),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.allocate(17).putLong(ack.offset()).putLong(ack.timestampMillis()).put((byte)42); data.flip();
                while(data.hasRemaining())file.write(data); file.force(true);
            }
            armed[0]=true;
            store.append(part,Optional.of(ImmutableBytes.copyOf(new byte[0])),ImmutableBytes.copyOf(new byte[]{99}),false);
            throw new AssertionError("failpoint not reached");
        }
    }
}
