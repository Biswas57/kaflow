package kaflow.storage;

import kaflow.broker.ImmutableBytes;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

final class StorageTestSupport {
    static final TopicPartition ID = new TopicPartition(new TopicId("orders"), 2);
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(123456789), ZoneOffset.UTC);
    static final ImmutableBytes EMPTY = bytes();
    static final String ZERO_LOG = "00000000000000000000.log";
    static final String ZERO_INDEX = "00000000000000000000.idx";

    static ImmutableBytes bytes(byte... values) { return ImmutableBytes.copyOf(values); }
    static StorageOptions options(long segmentBytes, int interval) {
        return new StorageOptions(segmentBytes, 100, 16384, 1048576, interval, 128, 2097152, 8);
    }
    static PartitionLog open(Path path) throws IOException {
        return PartitionLog.open(path, ID, StorageOptions.defaults(), CLOCK);
    }
    static StorageException fails(StorageException.Code code, Executable operation) {
        StorageException error = assertThrows(StorageException.class, operation);
        assertEquals(code, error.code());
        return error;
    }
    // Independent fixture construction: no production encoding/checksum helper is called.
    static byte[] header(byte[] topic, int partition, long base) {
        ByteBuffer data = ByteBuffer.allocate(32 + topic.length);
        data.putInt(0x4b464c47).putShort((short) 1).putShort((short) 0).putInt(data.capacity())
                .putInt(topic.length).putInt(partition).putLong(base).put(topic);
        return crc(data.array());
    }
    static byte[] frame(long offset, Optional<byte[]> key, byte[] payload) {
        int keyLength = key.map(v -> v.length).orElse(0);
        ByteBuffer data = ByteBuffer.allocate(36 + keyLength + payload.length);
        data.putInt(data.capacity()).putShort((short) 1).putShort((short) (key.isPresent() ? 1 : 0))
                .putLong(offset).putLong(CLOCK.millis()).putInt(key.isPresent() ? keyLength : -1).putInt(payload.length);
        key.ifPresent(data::put);
        data.put(payload);
        return crc(data.array());
    }
    static byte[] crc(byte[] data) {
        CRC32C checksum = new CRC32C();
        checksum.update(data, 0, data.length - 4);
        ByteBuffer.wrap(data).putInt(data.length - 4, (int) checksum.getValue());
        return data;
    }
    static void fixture(Path directory, byte[] header, byte[]... frames) throws IOException {
        Files.createDirectories(directory);
        int length = header.length + Arrays.stream(frames).mapToInt(v -> v.length).sum();
        ByteBuffer data = ByteBuffer.allocate(length).put(header);
        for (byte[] frame : frames) data.put(frame);
        Files.write(directory.resolve(ZERO_LOG), data.array());
    }

    static final class ControlledIo implements StorageIo {
        final StorageIo delegate = StorageIo.system();
        int readChunk = Integer.MAX_VALUE;
        int writeChunk = Integer.MAX_VALUE;
        boolean zeroReads;
        boolean zeroWrites;
        boolean alternatingZeroes;
        boolean armed;
        String targetSuffix = ".log";
        long failAfter = Long.MAX_VALUE;
        long faultBytes;
        boolean failCreate;
        boolean failClose;
        int opened;
        int closed;
        int live;
        int maxLive;
        int readCalls;
        int writeCalls;
        long readBytes;
        final IOException injected = new IOException("injected storage I/O failure");
        final IOException cleanup = new IOException("injected cleanup failure");

        @Override
        public File open(Path path, Mode mode) throws IOException {
            boolean target = path.toString().endsWith(targetSuffix);
            if (armed && failCreate && target && mode == Mode.CREATE) throw injected;
            File file = delegate.open(path, mode);
            opened++;
            maxLive = Math.max(maxLive, ++live);
            return new File() {
                boolean released;
                @Override public int read(ByteBuffer data, long position) throws IOException {
                    readCalls++;
                    if (zeroReads || (alternatingZeroes && readCalls % 2 == 0)) return 0;
                    int limit = data.limit();
                    data.limit(data.position() + Math.min(data.remaining(), readChunk));
                    try {
                        int count = file.read(data, position);
                        if (count > 0) readBytes += count;
                        return count;
                    } finally { data.limit(limit); }
                }
                @Override public int write(ByteBuffer data, long position) throws IOException {
                    writeCalls++;
                    if (zeroWrites || (alternatingZeroes && writeCalls % 2 == 0)) return 0;
                    if (armed && target && faultBytes >= failAfter) throw injected;
                    int count = Math.min(data.remaining(), writeChunk);
                    if (armed && target) count = (int) Math.min(count, failAfter - faultBytes);
                    int limit = data.limit();
                    data.limit(data.position() + count);
                    try {
                        int written = file.write(data, position);
                        if (armed && target) faultBytes += written;
                        return written;
                    } finally { data.limit(limit); }
                }
                @Override public long size() throws IOException { return file.size(); }
                @Override public void force() throws IOException { file.force(); }
                @Override public void close() throws IOException {
                    file.close();
                    if (!released) { released = true; closed++; live--; }
                    if (armed && failClose && target) { failClose = false; throw cleanup; }
                }
            };
        }
    }
}
