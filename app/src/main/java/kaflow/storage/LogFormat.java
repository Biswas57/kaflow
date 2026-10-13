package kaflow.storage;

import kaflow.broker.ImmutableBytes;
import kaflow.broker.StoredRecord;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32C;

import static kaflow.storage.StorageException.Code.*;

/** V1 disk encoding; package-private so the on-disk contract, not codec helpers, is the API. */
final class LogFormat {
    static final int SEGMENT_MAGIC = 0x4b464c47;
    static final int INDEX_MAGIC = 0x4b464958;
    static final int FRAME_PREFIX = 32;
    static final int FRAME_OVERHEAD = 36;
    static final int INDEX_HEADER = 32;
    static final int INDEX_ENTRY = 24;

    record Header(int length, int checksum) { }
    record Frame(StoredRecord record, int length) { }
    record IndexEntry(long offset, long position, int frameLength) { }

    static byte[] topicBytes(TopicPartition identity) throws StorageException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(identity.topic().value()));
            if (encoded.remaining() < 1 || encoded.remaining() > 255) {
                throw new StorageException(INVALID_ARGUMENT, "topic exceeds disk identity bounds");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new StorageException(INVALID_ARGUMENT, "topic cannot be encoded losslessly as UTF-8", e);
        }
    }

    static ByteBuffer segmentHeader(TopicPartition identity, byte[] topic, long base) {
        ByteBuffer data = ByteBuffer.allocate(32 + topic.length);
        data.putInt(SEGMENT_MAGIC).putShort((short) 1).putShort((short) 0).putInt(data.capacity())
                .putInt(topic.length).putInt(identity.partition()).putLong(base).put(topic);
        return checksumAndFlip(data);
    }

    static Header readHeader(StorageIo.File file, TopicPartition identity, byte[] topic, long base,
                             long fileSize, StorageOptions options) throws IOException {
        if (fileSize < 32) throw corrupt("incomplete segment header");
        ByteBuffer prefix = ByteBuffer.allocate(28);
        StorageIo.readFully(file, prefix, 0, options.zeroProgressLimit());
        prefix.flip();
        if (prefix.getInt() != SEGMENT_MAGIC) throw corrupt("segment magic");
        if (prefix.getShort() != 1) throw new StorageException(UNSUPPORTED_VERSION, "unsupported segment version");
        if (prefix.getShort() != 0) throw corrupt("segment flags");
        int length = prefix.getInt();
        int topicLength = prefix.getInt();
        int partition = prefix.getInt();
        long actualBase = prefix.getLong();
        if (topicLength < 1 || topicLength > 255 || length != 32L + topicLength || length > fileSize) {
            throw corrupt("segment header length");
        }
        ByteBuffer data = ByteBuffer.allocate(length);
        data.put(prefix.array());
        StorageIo.readFully(file, data, 28, options.zeroProgressLimit());
        verifyChecksum(data.array());
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data.array(), 28, topicLength)).toString();
        } catch (CharacterCodingException e) {
            throw new StorageException(CORRUPT, "malformed UTF-8 segment identity", e);
        }
        if (decoded.isBlank() || partition < 0 || actualBase < 0) throw corrupt("invalid segment identity/base");
        if (partition != identity.partition() || !Arrays.equals(topic, Arrays.copyOfRange(data.array(), 28, length - 4))) {
            throw new StorageException(WRONG_IDENTITY, "segment belongs to another qualified partition");
        }
        if (actualBase != base) throw corrupt("segment base differs from filename");
        return new Header(length, data.getInt(length - 4));
    }

    static int frameLength(Optional<ImmutableBytes> key, ImmutableBytes payload, StorageOptions options) throws StorageException {
        int keyLength = key.isPresent() ? key.orElseThrow().length() : 0;
        if (keyLength > options.maxKeyBytes() || payload.length() > options.maxPayloadBytes()) {
            throw new StorageException(INVALID_ARGUMENT, "key/payload exceeds storage maximum");
        }
        return Math.toIntExact(Math.addExact(FRAME_OVERHEAD, Math.addExact((long) keyLength, payload.length())));
    }

    static ByteBuffer frame(Optional<ImmutableBytes> key, ImmutableBytes payload, long offset, long timestamp, int length) {
        ByteBuffer data = ByteBuffer.allocate(length);
        data.putInt(length).putShort((short) 1).putShort((short) (key.isPresent() ? 1 : 0))
                .putLong(offset).putLong(timestamp)
                .putInt(key.isPresent() ? key.orElseThrow().length() : -1).putInt(payload.length());
        key.ifPresent(value -> data.put(value.asReadOnlyBuffer()));
        data.put(payload.asReadOnlyBuffer());
        return checksumAndFlip(data);
    }

    static Frame readFrame(StorageIo.File file, TopicPartition identity, long position, long end,
                           long expectedOffset, StorageOptions options) throws IOException {
        if (position < 0 || position > end || end - position < FRAME_OVERHEAD) throw corrupt("incomplete frame prefix");
        ByteBuffer prefix = ByteBuffer.allocate(FRAME_PREFIX);
        StorageIo.readFully(file, prefix, position, options.zeroProgressLimit());
        prefix.flip();
        int length = prefix.getInt();
        if (prefix.getShort() != 1) throw new StorageException(UNSUPPORTED_VERSION, "unsupported frame version");
        int flags = Short.toUnsignedInt(prefix.getShort());
        long offset = prefix.getLong();
        long timestamp = prefix.getLong();
        int keyLength = prefix.getInt();
        int payloadLength = prefix.getInt();
        if (flags > 1 || (flags == 0 && keyLength != -1) || (flags == 1 && keyLength < 0)) throw corrupt("frame flags/key length");
        if (payloadLength < 0 || payloadLength > options.maxPayloadBytes() || keyLength > options.maxKeyBytes()) {
            throw corrupt("frame length exceeds configured bounds");
        }
        long expectedLength = FRAME_OVERHEAD + (long) Math.max(0, keyLength) + payloadLength;
        if (length != expectedLength || length > end - position) throw corrupt("invalid or incomplete frame length");
        if (offset != expectedOffset || offset < 0 || offset == Long.MAX_VALUE) throw corrupt("noncontiguous or overflowing offset");
        ByteBuffer data = ByteBuffer.allocate(length);
        data.put(prefix.array());
        StorageIo.readFully(file, data, Math.addExact(position, FRAME_PREFIX), options.zeroProgressLimit());
        verifyChecksum(data.array());
        int payloadStart = FRAME_PREFIX + Math.max(0, keyLength);
        Optional<ImmutableBytes> key = flags == 0 ? Optional.empty()
                : Optional.of(ImmutableBytes.copyOf(Arrays.copyOfRange(data.array(), FRAME_PREFIX, payloadStart)));
        ImmutableBytes payload = ImmutableBytes.copyOf(Arrays.copyOfRange(data.array(), payloadStart, length - 4));
        return new Frame(new StoredRecord(identity, offset, timestamp, key, payload), length);
    }

    static ByteBuffer indexHeader(long base, int headerChecksum, int interval) {
        ByteBuffer data = ByteBuffer.allocate(INDEX_HEADER);
        data.putInt(INDEX_MAGIC).putShort((short) 1).putShort((short) 0).putInt(INDEX_HEADER)
                .putInt(interval).putLong(base).putInt(headerChecksum);
        return checksumAndFlip(data);
    }

    static ByteBuffer indexEntry(long offset, long position, int length) {
        ByteBuffer data = ByteBuffer.allocate(INDEX_ENTRY);
        data.putLong(offset).putLong(position).putInt(length);
        return checksumAndFlip(data);
    }

    static IndexEntry readIndexEntry(StorageIo.File file, long ordinal, long expectedOffset,
                                    long headerLength, long end, StorageOptions options) throws IOException {
        if (ordinal < 0 || ordinal > (options.segmentBytes() / FRAME_OVERHEAD) / options.indexInterval()) {
            throw corrupt("index ordinal out of bounds");
        }
        long position = Math.addExact(INDEX_HEADER, Math.multiplyExact(ordinal, INDEX_ENTRY));
        long indexSize = file.size();
        if (position > indexSize || INDEX_ENTRY > indexSize - position) throw corrupt("incomplete index entry");
        ByteBuffer data = ByteBuffer.allocate(INDEX_ENTRY);
        StorageIo.readFully(file, data, position, options.zeroProgressLimit());
        verifyChecksum(data.array());
        data.flip();
        long offset = data.getLong();
        long framePosition = data.getLong();
        int length = data.getInt();
        if (offset != expectedOffset || framePosition < headerLength || framePosition > end
                || length < FRAME_OVERHEAD || length > end - framePosition
                || length > FRAME_OVERHEAD + (long) options.maxKeyBytes() + options.maxPayloadBytes()) {
            throw corrupt("unsafe index entry");
        }
        return new IndexEntry(offset, framePosition, length);
    }

    static int checksum(byte[] bytes, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, length);
        return (int) crc.getValue();
    }

    private static ByteBuffer checksumAndFlip(ByteBuffer data) {
        data.putInt(checksum(data.array(), data.position()));
        return data.flip();
    }

    private static void verifyChecksum(byte[] bytes) throws StorageException {
        if (checksum(bytes, bytes.length - 4) != ByteBuffer.wrap(bytes).getInt(bytes.length - 4)) throw corrupt("checksum mismatch");
    }

    static StorageException corrupt(String message) {
        return new StorageException(CORRUPT, message);
    }
}
