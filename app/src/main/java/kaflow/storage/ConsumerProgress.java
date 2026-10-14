package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded per-group authoritative vectors. Durable group/topic slots, never session identities. */
final class ConsumerProgress {
    static final int MAGIC = 0x4b46434f;
    record Value(long revision, List<Long> offsets) {
        Value { offsets = List.copyOf(offsets); }
        static Value zero(int count) { return new Value(0, java.util.Collections.nCopies(count, 0L)); }
        Value update(int partition, long offset) throws IOException {
            if (revision == Long.MAX_VALUE) throw new StorageException(StorageException.Code.LIMIT, "progress revision exhausted");
            var next = new ArrayList<>(offsets); next.set(partition, offset); return new Value(revision + 1, next);
        }
    }
    static ByteBuffer encode(UUID store, int group, int topic, Value value) {
        ByteBuffer b = ByteBuffer.allocate(52 + 8 * value.offsets.size());
        b.putInt(MAGIC).putShort((short) 1).putShort((short) 0).putInt(b.capacity())
                .putLong(store.getMostSignificantBits()).putLong(store.getLeastSignificantBits())
                .putInt(group).putInt(topic).putInt(value.offsets.size()).putLong(value.revision);
        for (long offset : value.offsets) b.putLong(offset);
        b.putInt(LogFormat.checksum(b.array(), b.position())); return b.flip();
    }
    static Value read(Path path, UUID store, int group, int topic, int count, Persistence disk, StorageOptions options) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw LogFormat.corrupt("missing progress authority");
        try (var file = disk.io.open(path, StorageIo.Mode.READ)) {
            int size = 52 + 8 * count;
            if (file.size() != size) throw LogFormat.corrupt("progress length");
            ByteBuffer b = ByteBuffer.allocate(size); StorageIo.readFully(file, b, 0, options.zeroProgressLimit()); b.flip();
            if (b.getInt() != MAGIC) throw LogFormat.corrupt("progress magic");
            if (b.getShort() != 1) throw new StorageException(StorageException.Code.UNSUPPORTED_VERSION, "progress version");
            if (b.getShort() != 0 || b.getInt() != size || !new UUID(b.getLong(), b.getLong()).equals(store)
                    || b.getInt() != group || b.getInt() != topic || b.getInt() != count) throw LogFormat.corrupt("progress identity");
            long revision = b.getLong(); List<Long> offsets = new ArrayList<>();
            for (int i = 0; i < count; i++) { long offset = b.getLong(); if (offset < 0) throw LogFormat.corrupt("negative progress"); offsets.add(offset); }
            if (revision < 0 || revision == 0 && offsets.stream().anyMatch(n -> n != 0)
                    || b.getInt() != LogFormat.checksum(b.array(), size - 4)) throw LogFormat.corrupt("progress checksum/revision");
            return new Value(revision, offsets);
        }
    }
}
