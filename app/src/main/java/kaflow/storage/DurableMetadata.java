package kaflow.storage;

import kaflow.broker.TopicId;
import kaflow.groups.GroupId;
import kaflow.groups.GroupOptions;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static kaflow.storage.StorageException.Code.*;

/** Exact V1/V2 catalog and V1 checkpoint codecs. Metadata is bounded before allocation. */
final class DurableMetadata {
    static final int CATALOG = 0x4b464341;
    static final int CHECKPOINT = 0x4b464350;
    record Topic(int slot, TopicId id, int partitions) { }
    record Group(GroupId id, TopicId topic) { }
    record Catalog(UUID store, List<Topic> topics, List<Group> groups, boolean progress) {
        Catalog(UUID store, List<Topic> topics, List<Group> groups) { this(store, topics, groups, false); }
        Catalog(UUID store, List<Topic> topics) { this(store, topics, List.of()); }
        Catalog { topics = List.copyOf(topics); groups = List.copyOf(groups); }
    }
    record Checkpoint(long end, long base, long bytes, long unkeyed) { }

    static ByteBuffer catalog(Catalog value) throws IOException {
        List<byte[]> names = new ArrayList<>();
        int length = value.groups.isEmpty() && !value.progress ? 44 : 48;
        for (Topic t : value.topics) {
            byte[] name = LogFormat.topicBytes(new TopicPartition(t.id, 0));
            names.add(name); length += 12 + name.length;
        }
        for (Group g : value.groups) length = Math.addExact(length, 8 + g.id.byteLength());
        ByteBuffer b = prefix(CATALOG, length, value.store);
        if (!value.groups.isEmpty()) b.putShort(4, (short) 2);
        if (value.progress) b.putShort(4, (short) 3);
        b.putLong(value.topics.size() + value.groups.size()).putInt(value.topics.size());
        if (!value.groups.isEmpty() || value.progress) b.putInt(value.groups.size());
        for (int i = 0; i < value.topics.size(); i++) {
            Topic t = value.topics.get(i); byte[] name = names.get(i);
            b.putInt(t.slot).putInt(t.partitions).putInt(name.length).put(name);
        }
        for (Group g : value.groups) {
            byte[] name = g.id.value().getBytes(StandardCharsets.UTF_8);
            int slot = value.topics.stream().filter(t -> t.id.equals(g.topic)).findFirst().orElseThrow().slot;
            b.putInt(name.length).putInt(slot).put(name);
        }
        return finish(b);
    }

    static Catalog readCatalog(Path path, Persistence disk, DurableOptions options) throws IOException {
        return readCatalog(path, disk, options, GroupOptions.defaults());
    }

    static Catalog readCatalog(Path path, Persistence disk, DurableOptions options, GroupOptions groups) throws IOException {
        ByteBuffer b = read(path, CATALOG, 44, groups.maxCatalogBytes(), disk, options.storage());
        UUID store = new UUID(b.getLong(), b.getLong());
        long generation = b.getLong(); int count = b.getInt();
        int groupCount = 0;
        if (b.getShort(4) >= 2) {
            require(b, 4); groupCount = b.getInt();
            if (groupCount < 1 || groupCount > groups.maxGroups()) throw LogFormat.corrupt("catalog group count");
        }
        if (count < 0 || count > options.maxTopics() || generation != count + groupCount) throw LogFormat.corrupt("catalog count/generation");
        List<Topic> topics = new ArrayList<>(); HashSet<TopicId> names = new HashSet<>(); int total = 0;
        for (int i = 0; i < count; i++) {
            require(b, 12); int slot = b.getInt(); int partitions = b.getInt(); TopicId id = topic(b);
            if (slot != i || partitions < 1 || partitions > options.maxPartitionsPerTopic() || !names.add(id)
                    || (total += partitions) > options.maxPartitions()) throw LogFormat.corrupt("catalog topic bounds");
            topics.add(new Topic(slot, id, partitions));
        }
        List<Group> definitions = new ArrayList<>(); HashSet<GroupId> ids = new HashSet<>();
        for (int i = 0; i < groupCount; i++) {
            require(b, 8); int length = b.getInt(), slot = b.getInt();
            if (length < 1 || length > 255 || slot < 0 || slot >= topics.size()) throw LogFormat.corrupt("catalog group binding");
            require(b, length);
            try {
                ByteBuffer slice = b.slice(); slice.limit(length);
                GroupId id = new GroupId(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString());
                b.position(b.position() + length);
                if (!ids.add(id)) throw LogFormat.corrupt("duplicate group identity");
                definitions.add(new Group(id, topics.get(slot).id));
            } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
                throw new StorageException(CORRUPT, "invalid group identity", e);
            }
        }
        if (b.remaining() != 4) throw LogFormat.corrupt("catalog trailing bytes");
        return new Catalog(store, topics, definitions, b.getShort(4) == 3);
    }

    static ByteBuffer checkpoint(UUID store, Topic topic, int partition, Checkpoint value) throws IOException {
        byte[] name = LogFormat.topicBytes(new TopicPartition(topic.id, partition));
        ByteBuffer b = prefix(CHECKPOINT, 84 + name.length, store);
        b.putInt(topic.slot).putInt(partition).putInt(name.length).putLong(value.end).putLong(value.end)
                .putLong(value.base).putLong(value.bytes).putLong(value.unkeyed).put(name);
        return finish(b);
    }

    static Checkpoint readCheckpoint(Path path, UUID store, Topic topic, int partition,
                                     Persistence disk, StorageOptions options) throws IOException {
        ByteBuffer b = read(path, CHECKPOINT, 85, 339, disk, options);
        UUID found = new UUID(b.getLong(), b.getLong());
        int slot = b.getInt(); int part = b.getInt(); int topicLength = b.getInt();
        long generation = b.getLong(), end = b.getLong(), base = b.getLong(), bytes = b.getLong(), unkeyed = b.getLong();
        TopicId id = topic(b, topicLength);
        int header = 32 + LogFormat.topicBytes(new TopicPartition(topic.id, partition)).length;
        if (!store.equals(found) || slot != topic.slot || part != partition || !id.equals(topic.id)
                || b.remaining() != 4 || end < 0 || generation != end || base < 0
                || (end == 0 ? base != 0 || bytes != header : base >= end)
                || bytes < header || bytes > options.segmentBytes() || unkeyed < 0 || unkeyed > end) {
            throw LogFormat.corrupt("invalid publication checkpoint");
        }
        return new Checkpoint(end, base, bytes, unkeyed);
    }

    /** Inspect even discarded recognizable authoritative scratch versions before repair. */
    static void checkVersion(Path path, int magic, Persistence disk, StorageOptions options) throws IOException {
        try (StorageIo.File f = disk.io.open(path, StorageIo.Mode.READ)) {
            if (f.size() < 6) return;
            ByteBuffer b = ByteBuffer.allocate(6);
            StorageIo.readFully(f, b, 0, options.zeroProgressLimit()); b.flip();
            if (b.getInt() == magic && !supported(magic, b.getShort())) throw new StorageException(UNSUPPORTED_VERSION, "unsupported metadata version");
        }
    }

    private static boolean supported(int magic, short version) {
        return version == 1 || magic == CATALOG && (version == 2 || version == 3);
    }

    private static ByteBuffer prefix(int magic, int length, UUID store) {
        return ByteBuffer.allocate(length).putInt(magic).putShort((short) 1).putShort((short) 0).putInt(length)
                .putLong(store.getMostSignificantBits()).putLong(store.getLeastSignificantBits());
    }

    private static ByteBuffer finish(ByteBuffer b) {
        b.putInt(LogFormat.checksum(b.array(), b.position())); return b.flip();
    }

    private static ByteBuffer read(Path path, int magic, int min, int max, Persistence disk, StorageOptions options) throws IOException {
        try (StorageIo.File f = disk.io.open(path, StorageIo.Mode.READ)) {
            long size = f.size();
            if (size < min || size > max) throw LogFormat.corrupt("metadata size");
            ByteBuffer prefix = ByteBuffer.allocate(12);
            StorageIo.readFully(f, prefix, 0, options.zeroProgressLimit()); prefix.flip();
            if (prefix.getInt() != magic) throw LogFormat.corrupt("metadata magic");
            if (!supported(magic, prefix.getShort())) throw new StorageException(UNSUPPORTED_VERSION, "unsupported metadata version");
            if (prefix.getShort() != 0 || prefix.getInt() != size) throw LogFormat.corrupt("metadata flags/length");
            ByteBuffer b = ByteBuffer.allocate((int) size); b.put(prefix.array());
            StorageIo.readFully(f, b, 12, options.zeroProgressLimit());
            if (b.getInt((int) size - 4) != LogFormat.checksum(b.array(), (int) size - 4)) throw LogFormat.corrupt("metadata checksum");
            b.position(12); return b;
        }
    }

    private static TopicId topic(ByteBuffer b) throws IOException { require(b, 4); return topic(b, b.getInt()); }
    private static TopicId topic(ByteBuffer b, int length) throws IOException {
        if (length < 1 || length > 255) throw LogFormat.corrupt("metadata topic length");
        require(b, length);
        try {
            ByteBuffer slice = b.slice(); slice.limit(length);
            String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString();
            b.position(b.position() + length); return new TopicId(name);
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
            throw new StorageException(CORRUPT, "invalid metadata identity", e);
        }
    }
    private static void require(ByteBuffer b, int length) throws IOException {
        if (b.remaining() - 4 < length) throw LogFormat.corrupt("incomplete metadata");
    }
}
