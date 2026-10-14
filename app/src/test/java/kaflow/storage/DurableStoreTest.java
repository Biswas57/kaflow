package kaflow.storage;

import kaflow.broker.ImmutableBytes;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class DurableStoreTest {
    @TempDir Path directory;

    @Test void exactBytesAndMetadataSurviveRotationAndRestart() throws Exception {
        var key = ImmutableBytes.copyOf(new byte[0]);
        var payload = ImmutableBytes.copyOf(new byte[]{0, -1, -128, -61, 40});
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            store.createTopic(TOPIC, 2);
            for (int i = 0; i < 12; i++) {
                var receipt = store.append(PART, i % 2 == 0 ? Optional.of(key) : Optional.empty(), payload, i % 2 == 1);
                assertEquals(i, receipt.offset()); assertEquals(PART, receipt.partition()); assertEquals(CLOCK.millis(), receipt.timestampMillis());
            }
        }
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            assertEquals(12, store.recovery().ends().get(PART));
            var records = store.inspect(PART, 0, 128, 2097152);
            assertEquals(12, records.size());
            for (int i = 0; i < records.size(); i++) {
                var r = records.get(i); assertEquals(i, r.offset()); assertEquals(PART, r.partition());
                assertEquals(CLOCK.millis(), r.timestampMillis()); assertEquals(payload, r.payload());
                assertEquals(i % 2 == 0, r.key().isPresent()); if (r.key().isPresent()) assertEquals(key, r.key().get());
            }
            assertThrows(UnsupportedOperationException.class, () -> records.clear());
            assertEquals(12, store.append(PART, Optional.empty(), EMPTY, true).offset());
        }
    }

    @Test void orderingAndCheckpointCostDoNotScanHistoryOrRewriteCatalog() throws Exception {
        Events disk = new Events();
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, disk)) {
            store.createTopic(TOPIC, 1); byte[] catalog = Files.readAllBytes(directory.resolve("catalog"));
            disk.events.clear();
            store.append(PART, Optional.empty(), EMPTY, true);
            assertEquals(List.of("data-written", "data-forced", "segment-directory-synced", "checkpoint-written",
                    "checkpoint-forced", "checkpoint-replaced", "checkpoint-synced", "durable-complete"), disk.events);
            assertArrayEquals(catalog, Files.readAllBytes(directory.resolve("catalog")));
            assertEquals(90, Files.size(checkpoint(directory))); // 84 fixed bytes + literal six UTF-8 bytes.
            ByteBuffer checkpoint = ByteBuffer.wrap(Files.readAllBytes(checkpoint(directory)));
            assertEquals(0x4b464350, checkpoint.getInt()); assertEquals(1, checkpoint.getShort());
            assertEquals(1, checkpoint.getLong(40)); assertEquals(1, checkpoint.getLong(48));
            assertEquals(1, checkpoint.getLong(72));
        }
    }

    static Stream<String> boundaries() {
        return Stream.of("data-written", "data-forced", "segment-directory-synced", "checkpoint-written",
                "checkpoint-forced", "checkpoint-replaced", "checkpoint-synced", "durable-complete");
    }
    @ParameterizedTest @MethodSource("boundaries")
    void failureAtEveryPublicationBoundaryPreservesAcknowledgedPrefix(String point) throws Exception {
        Events disk = new Events();
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, disk)) {
            store.createTopic(TOPIC, 1); store.append(PART, Optional.empty(), ImmutableBytes.copyOf(new byte[]{1}), true);
            disk.fail = point;
            assertSame(disk.injected, assertThrows(IOException.class, () -> store.append(PART, Optional.of(EMPTY), EMPTY, false)));
            assertThrows(IOException.class, () -> store.append(PART, Optional.empty(), EMPTY, true));
        }
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            boolean replaced = List.of("checkpoint-replaced", "checkpoint-synced", "durable-complete").contains(point);
            var records = store.inspect(PART, 0, 128, 2097152);
            assertEquals(replaced ? 2 : 1, records.size()); assertArrayEquals(new byte[]{1}, records.get(0).payload().toByteArray());
            assertEquals(records.size(), store.append(PART, Optional.empty(), EMPTY, true).offset());
        }
    }

    @ParameterizedTest @ValueSource(strings={"catalog", "checkpoint", "record", "header", "missing-log", "truncated", "wrong-identity"})
    void publishedDamageFailsClosedWithoutRepair(String damage) throws Exception {
        seed();
        Path target = switch (damage) { case "catalog" -> directory.resolve("catalog"); case "checkpoint" -> checkpoint(directory); default -> log(directory); };
        byte[] original = Files.readAllBytes(target);
        if (damage.equals("missing-log")) Files.delete(target);
        else if (damage.equals("truncated")) Files.write(target, Arrays.copyOf(original, original.length - 1));
        else {
            int index = switch (damage) { case "catalog", "checkpoint" -> original.length - 1; case "header" -> 28; case "wrong-identity" -> 19; default -> 71; };
            original[index] ^= 1; Files.write(target, original);
        }
        byte[] damaged = Files.exists(target) ? Files.readAllBytes(target) : null;
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        if (damaged != null) assertArrayEquals(damaged, Files.readAllBytes(target)); else assertFalse(Files.exists(target));
    }

    @ParameterizedTest @ValueSource(strings={"catalog", "checkpoint"})
    void missingAuthorityNeverFallsBackToTemporaryFile(String what) throws Exception {
        seed(); Path target = what.equals("catalog") ? directory.resolve("catalog") : checkpoint(directory);
        Files.move(target, target.resolveSibling(target.getFileName()+".tmp"));
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        assertFalse(Files.exists(target));
    }

    @Test void losingAnEmptyStoresCatalogCannotBeMistakenForNewStorage() throws Exception {
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            assertTrue(store.recovery().topics().isEmpty());
        }
        Files.delete(directory.resolve("catalog"));
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        assertFalse(Files.exists(directory.resolve("catalog")));
    }

    @Test void interruptedBootstrapAndDamagedRootMarkerFailClosed() throws Exception {
        Events disk = new Events(); disk.fail = "root-marked";
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, disk));
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        Path marker = directory.resolve(".kaflow.lock"); byte[] bad = Files.readAllBytes(marker); bad[0] ^= 1;
        Files.write(marker, bad);
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        assertArrayEquals(bad, Files.readAllBytes(marker));
    }

    @Test void completeAndPartialUnpublishedTailsAreDiscardedAndRecoveryCanRepeat() throws Exception {
        seed();
        byte[] before = Files.readAllBytes(log(directory));
        byte[] tail = StorageTestSupport.frame(1, Optional.empty(), new byte[]{2});
        Files.write(log(directory), tail, StandardOpenOption.APPEND); Files.write(log(directory), new byte[]{0,0,0}, StandardOpenOption.APPEND);
        Events disk = new Events(); disk.fail = "recovery-truncated";
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, disk));
        assertArrayEquals(before, Files.readAllBytes(log(directory)));
        for (int i = 0; i < 2; i++) try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            assertEquals(1, store.recovery().ends().get(PART)); assertEquals(1, store.inspect(PART, 0, 2, 100).size());
        }
    }

    @Test void incompleteRotationIsRemovedButPublishedLaterSegmentsAreNeverDiscarded() throws Exception {
        seed(); Path tail = log(directory).resolveSibling("00000000000000000001.log");
        Files.write(tail, new byte[]{0x4b,0x46,0x4c});
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            assertFalse(Files.exists(tail)); assertEquals(1, store.recovery().ends().get(PART));
        }
    }

    @Test void unsupportedVersionInLaterPartitionPreventsEarlierTailRepair() throws Exception {
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) { store.createTopic(TOPIC, 2); }
        Files.write(log(directory), new byte[]{1,2,3}, StandardOpenOption.APPEND);
        Path second = directory.resolve("t-00000000/p-00000001.pub");
        byte[] bad = Files.readAllBytes(second); ByteBuffer.wrap(bad).putShort(4, (short) 2); Files.write(second, StorageTestSupport.crc(bad));
        byte[] before = Files.readAllBytes(log(directory));
        var error = assertThrows(StorageException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        assertEquals(StorageException.Code.UNSUPPORTED_VERSION, error.code());
        assertArrayEquals(before, Files.readAllBytes(log(directory))); assertArrayEquals(bad, Files.readAllBytes(second));
    }

    @Test void indexesAreRebuiltButDoNotConcealPublishedCorruption() throws Exception {
        seed(); Path index = log(directory).resolveSibling(StorageTestSupport.ZERO_INDEX); Files.write(index, new byte[]{1});
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) { assertEquals(1, store.inspect(PART, 0, 5, 100).size()); }
        assertTrue(Files.size(index) > 1);
        byte[] bad = Files.readAllBytes(log(directory)); bad[bad.length-1] ^= 1; Files.write(log(directory), bad); Files.delete(index);
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())); assertFalse(Files.exists(index));
    }

    @ParameterizedTest @ValueSource(strings={"topic-data-forced", "initial-checkpoint-written", "initial-checkpoint-replaced", "topic-directories-synced", "catalog-written", "catalog-replaced", "catalog-synced"})
    void interruptedTopicCreationUsesOnlyAuthoritativeCatalog(String point) throws Exception {
        Events disk = new Events();
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, disk)) {
            disk.fail = point; assertThrows(IOException.class, () -> store.createTopic(TOPIC, 2));
        }
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            boolean published = point.equals("catalog-replaced") || point.equals("catalog-synced");
            assertEquals(published ? 1 : 0, store.recovery().topics().size());
            if (!published) store.createTopic(TOPIC, 2);
        }
    }

    @Test void legacyAndInvalidUtf8IdentitiesRejectWithoutMigration() throws Exception {
        Path legacy = directory.resolve("legacy");
        try (var log = PartitionLog.open(legacy, PART, OPTIONS.storage(), CLOCK)) { log.append(Optional.empty(), EMPTY); }
        assertThrows(IOException.class, () -> DurableStore.open(legacy, OPTIONS, CLOCK, new Persistence()));
        try (var store = DurableStore.open(directory.resolve("new"), OPTIONS, CLOCK, new Persistence())) {
            assertThrows(IOException.class, () -> store.createTopic(new TopicId("bad\ud800"), 1));
            assertTrue(store.recovery().topics().isEmpty());
        }
    }

    @Test void unsupportedAtomicReplaceAndDirectoryForceNeverProduceSuccess() throws Exception {
        seed();
        Persistence noAtomic = new Persistence() { @Override void replace(Path a, Path b) throws IOException { throw new java.nio.file.AtomicMoveNotSupportedException("a","b","unsupported"); } };
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, noAtomic)) { assertThrows(IOException.class, () -> store.append(PART, Optional.empty(), EMPTY, true)); }
        Persistence noSync = new Persistence() { @Override void syncDirectory(Path p) throws IOException { throw new IOException("directory force unsupported"); } };
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, noSync));
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) { assertEquals(1, store.recovery().ends().get(PART)); }
    }

    @Test void uncataloguedTopicContainingRecordsIsInconsistentNotDisposableScratch() throws Exception {
        seed();
        var old = DurableMetadata.readCatalog(directory.resolve("catalog"), new Persistence(), OPTIONS);
        Files.write(directory.resolve("catalog"), DurableMetadata.catalog(new DurableMetadata.Catalog(old.store(), List.of())).array());
        byte[] before = Files.readAllBytes(log(directory));
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        assertArrayEquals(before, Files.readAllBytes(log(directory)));
        byte[] truncated = Arrays.copyOf(before, 38);
        Files.write(log(directory), truncated);
        assertThrows(IOException.class, () -> DurableStore.open(directory, OPTIONS, CLOCK, new Persistence()));
        assertArrayEquals(truncated, Files.readAllBytes(log(directory)));
    }

    private void seed() throws Exception {
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) { store.createTopic(TOPIC, 1); store.append(PART, Optional.empty(), EMPTY, true); }
    }
}
