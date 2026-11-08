package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import kaflow.broker.ImmutableBytes;
import kaflow.groups.GroupId;
import kaflow.groups.GroupOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RetentionStoreTest {
    @TempDir Path directory;
    static final LifecycleOptions RETAIN = new LifecycleOptions(200, 1L << 24, 1L << 20);
    DurableStore open(Persistence disk) throws IOException { return DurableStore.open(directory, OPTIONS, GroupOptions.defaults(), RETAIN, CLOCK, disk); }
    void seed(DurableStore store) throws Exception {
        store.createTopic(TOPIC, 1);
        for (int i = 0; i < 10; i++) assertEquals(i, store.append(PART, Optional.of(EMPTY), ImmutableBytes.copyOf(new byte[]{(byte)i, 0, -1}), true).offset());
    }
    static Path segment(Path root, long base, String suffix) { return log(root).resolveSibling(String.format(java.util.Locale.ROOT, "%020d%s", base, suffix)); }

    @Test void disabledPolicyPreservesHistoryAndDoesNotUpgrade() throws Exception {
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            seed(store); store.maintain(PART);
            assertEquals(0, store.snapshot(PART).start); assertEquals(10, store.inspect(PART, 0, 128, 100).size());
            assertEquals(1, ByteBuffer.wrap(Files.readAllBytes(directory.resolve("catalog"))).getShort(4));
            assertEquals(1, ByteBuffer.wrap(Files.readAllBytes(checkpoint(directory))).getShort(4));
        }
    }
    @Test void prefixRetirementPreservesActiveBytesOffsetsAndRestart() throws Exception {
        try (var store = open(new Persistence())) {
            seed(store); long before = store.quota.view().accountedBytes();
            var result = store.maintain(PART); assertFalse(result.again());
            assertEquals(9, result.snapshot().start); assertEquals(10, result.snapshot().end);
            assertEquals(4, ByteBuffer.wrap(Files.readAllBytes(directory.resolve("catalog"))).getShort(4));
            assertFalse(Files.exists(log(directory))); assertTrue(Files.exists(segment(directory, 9, ".log")));
            assertTrue(store.quota.view().accountedBytes() < before); assertEquals(0, store.quota.view().reservedBytes());
            var record = store.inspect(PART, 9, 128, 100).get(0);
            assertEquals(9, record.offset()); assertArrayEquals(new byte[]{9,0,-1}, record.payload().toByteArray());
            assertTrue(record.key().isPresent()); assertEquals(0, record.key().get().length());
            assertEquals(PART, record.partition()); assertEquals(CLOCK.millis(), record.timestampMillis());
        }
        // Disabling future policy never resurrects previously authorized expiration.
        for (int attempt = 0; attempt < 2; attempt++) try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            assertEquals(9, store.snapshot(PART).start); assertEquals(10 + attempt, store.recovery().ends().get(PART));
            assertEquals(10 + attempt, store.append(PART, Optional.empty(), EMPTY, true).offset());
        }
    }
    @Test void admittedPinsPrecedeOpenAndKeepSnapshotUntilRealRelease() throws Exception {
        try (var store = open(new Persistence())) {
            seed(store); var old = store.snapshot(PART); long before = store.quota.view().accountedBytes();
            try (var handle = old.acquire(0, 10)) {
                assertEquals(4, old.pins.count()); store.maintain(PART);
                assertEquals(9, store.snapshot(PART).start); assertTrue(Files.exists(log(directory)));
                assertEquals(before, store.quota.view().accountedBytes()); assertEquals(3, store.quota.view().pendingDeletion());
                assertEquals(10, handle.read(0, 10, 100).records().size());
                assertEquals(DurableException.Code.OUT_OF_RANGE, assertThrows(DurableException.class, () -> old.acquire(0, 1)).code());
            }
            assertEquals(0, old.pins.count()); store.maintain(PART); assertFalse(Files.exists(log(directory)));
            assertEquals(0, store.quota.view().pendingDeletion()); assertTrue(store.quota.view().accountedBytes() < before);
        }
    }
    @ParameterizedTest @ValueSource(strings={"retention-written","retention-forced","retention-replaced","retention-synced",
            "retention-published","retention-delete-before","retention-log-deleted","retention-index-deleted","retention-delete-synced"})
    void everyInterruptedAuthorizationAndDeletionBoundaryRecovers(String point) throws Exception {
        Events disk = new Events();
        try (var store = open(disk)) { seed(store); disk.fail = point; assertSame(disk.injected, assertThrows(IOException.class, () -> store.maintain(PART))); }
        boolean installed = !point.equals("retention-written") && !point.equals("retention-forced");
        for (int i = 0; i < 2; i++) try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) {
            assertEquals(installed ? 9 : 0, store.snapshot(PART).start); assertEquals(10, store.snapshot(PART).end);
            var records = store.inspect(PART, installed ? 9 : 0, 128, 100);
            assertEquals(installed ? 1 : 10, records.size()); assertEquals(9, records.get(records.size()-1).offset());
        }
    }
    @ParameterizedTest @ValueSource(strings={"retention-upgrade-written","retention-upgrade-forced","retention-upgrade-replaced","retention-upgrade-synced",
            "retention-catalog-written","retention-catalog-forced","retention-catalog-replaced","retention-catalog-synced"})
    void interruptedUpgradeNeverAuthorizesDeletion(String point) throws Exception {
        try (var store = DurableStore.open(directory, OPTIONS, CLOCK, new Persistence())) { seed(store); }
        Events disk = new Events(); disk.fail = point; assertThrows(IOException.class, () -> open(disk));
        try (var store = open(new Persistence())) { assertEquals(0, store.snapshot(PART).start); assertEquals(10, store.inspect(PART, 0, 128, 100).size()); store.maintain(PART); }
        try (var store = open(new Persistence())) { assertEquals(9, store.snapshot(PART).start); assertEquals(10, store.snapshot(PART).end); }
    }
    @ParameterizedTest @ValueSource(strings={"missing-checkpoint","corrupt-checkpoint","missing-retained","corrupt-retained","v1-checkpoint","unsupported-expired","unknown-file"})
    void retainedDamageAndUnsupportedFilesFailClosed(String damage) throws Exception {
        try (var store = open(new Persistence())) {
            seed(store);
            // Keep retired files to exercise recognizable unknown versions before cleanup.
            try (var handle = store.snapshot(PART).acquire()) { store.maintain(PART); }
        }
        Path target = checkpoint(directory);
        switch (damage) {
            case "missing-checkpoint" -> Files.delete(target);
            case "corrupt-checkpoint" -> { byte[] b = Files.readAllBytes(target); b[b.length-1] ^= 1; Files.write(target,b); }
            case "missing-retained" -> Files.delete(segment(directory,9,".log"));
            case "corrupt-retained" -> { target=segment(directory,9,".log"); byte[] b=Files.readAllBytes(target); b[b.length-1]^=1; Files.write(target,b); }
            case "v1-checkpoint" -> { var catalog=DurableMetadata.readCatalog(directory.resolve("catalog"),new Persistence(),OPTIONS); var t=catalog.topics().get(0); var cp=DurableMetadata.readCheckpoint(target,catalog.store(),t,0,new Persistence(),OPTIONS.storage()); Files.write(target,DurableMetadata.checkpoint(catalog.store(),t,0,new DurableMetadata.Checkpoint(cp.end(),cp.base(),cp.bytes(),cp.unkeyed())).array()); }
            case "unsupported-expired" -> { byte[] b=Files.readAllBytes(log(directory)); ByteBuffer.wrap(b).putShort(4,(short)99); Files.write(log(directory),StorageTestSupport.crc(b)); }
            case "unknown-file" -> Files.write(log(directory).resolveSibling("unknown"), new byte[]{1});
        }
        assertThrows(IOException.class, () -> open(new Persistence()));
        assertTrue(Files.exists(log(directory)), "validation must precede expired cleanup");
    }
    @Test void recoveryCleanupCanBeInterruptedAndRepeated() throws Exception {
        Events disk = new Events();
        try (var store = open(disk)) { seed(store); disk.fail="retention-published"; assertThrows(IOException.class, () -> store.maintain(PART)); }
        for (String point : List.of("recovery-deleted", "recovery-synced")) {
            Events recovery=new Events(); recovery.fail=point; assertThrows(IOException.class, () -> open(recovery));
        }
        try (var store=open(new Persistence())) { assertEquals(9,store.snapshot(PART).start); assertEquals(10,store.append(PART,Optional.empty(),EMPTY,false).offset()); }
    }
    @Test void expiredConsumerProgressRemainsValidAndResetIsExplicit() throws Exception {
        var group=new GroupId("lagging");
        try (var store=open(new Persistence())) {
            seed(store); store.createGroup(group,TOPIC); assertEquals(1,store.commit(group,PART,1,false)); store.maintain(PART);
            assertEquals(1,store.commit(group,PART,0,false)); assertEquals(1,store.commit(group,PART,1,false));
            assertEquals(StorageException.Code.OUT_OF_RANGE,assertThrows(StorageException.class,()->store.commit(group,PART,2,false)).code());
            assertThrows(StorageException.class,()->store.commit(group,PART,1,true));
        }
        try (var store=open(new Persistence())) { assertEquals(List.of(1L),store.recovery().progress().get(group)); assertEquals(9,store.commit(group,PART,9,true)); }
        try (var store=open(new Persistence())) { assertEquals(List.of(9L),store.recovery().progress().get(group)); }
    }
    @Test void maintenanceDeletesAtMostFourSegmentsPerTurn() throws Exception {
        try (var store=open(new Persistence())) {
            store.createTopic(TOPIC,1); for(int i=0;i<28;i++)store.append(PART,Optional.empty(),EMPTY,false);
            var first=store.maintain(PART); assertTrue(first.again()); assertEquals(27,first.snapshot().start);
            assertEquals(5,store.quota.view().pendingDeletion()); assertTrue(Files.exists(segment(directory,12,".log")));
            assertTrue(store.maintain(PART).again()); assertFalse(store.maintain(PART).again()); assertEquals(0,store.quota.view().pendingDeletion());
        }
    }
    @ParameterizedTest @ValueSource(longs={-1,1,10,Long.MAX_VALUE})
    void checksummedButInvalidRetainedBoundaryIsNeverInferredFromFiles(long start) throws Exception {
        try(var store=open(new Persistence())){seed(store);}
        byte[] bytes=Files.readAllBytes(checkpoint(directory));ByteBuffer.wrap(bytes).putLong(80,start);Files.write(checkpoint(directory),StorageTestSupport.crc(bytes));
        byte[] before=Files.readAllBytes(log(directory));assertThrows(IOException.class,()->open(new Persistence()));assertArrayEquals(before,Files.readAllBytes(log(directory)));
    }
    @ParameterizedTest @ValueSource(strings={"metadata","unlink","directory-force"})
    void realPersistencePrimitiveFailuresLatchWithoutPrematureQuotaCredit(String phase) throws Exception {
        var io=new StorageTestSupport.ControlledIo();boolean[] armed={false};
        var failure=new java.nio.file.FileSystemException("managed-file",null,"No space left on device");
        Persistence disk=new Persistence(io){
            @Override void delete(Path path)throws IOException{if(armed[0]&&phase.equals("unlink"))throw failure;super.delete(path);}
            @Override void syncDirectory(Path path)throws IOException{if(armed[0]&&phase.equals("directory-force")&&path.getFileName().toString().equals("p-00000000"))throw failure;super.syncDirectory(path);}
        };
        try(var store=open(disk)) {
            seed(store);long before=store.quota.view().accountedBytes();armed[0]=true;
            if(phase.equals("metadata")){io.targetSuffix=".pub.tmp";io.failAfter=5;io.armed=true;}
            assertThrows(IOException.class,()->store.maintain(PART));assertEquals(before,store.quota.view().accountedBytes());
            assertThrows(IOException.class,()->store.append(PART,Optional.empty(),EMPTY,false));
        }
        assertEquals(0,io.live);
        try(var store=open(new Persistence())){assertEquals(10,store.snapshot(PART).end);assertEquals(phase.equals("metadata")?0:9,store.snapshot(PART).start);}
    }

    @Test void retentionMetadataFailureKeepsPrimaryAndSuppressedCleanupFailure() throws Exception {
        var io=new StorageTestSupport.ControlledIo();
        try(var store=open(new Persistence(io))){seed(store);io.targetSuffix=".pub.tmp";io.failAfter=5;io.failClose=true;io.armed=true;
            var error=assertThrows(IOException.class,()->store.maintain(PART));assertSame(io.injected,error);assertEquals(List.of(io.cleanup),List.of(error.getSuppressed()));}
        assertEquals(0,io.live);try(var store=open(new Persistence())){assertEquals(0,store.snapshot(PART).start);assertEquals(10,store.snapshot(PART).end);}
    }
    @Test void pinnedDescriptorsAndFilesRemainBoundedWhenReclamationCannotProgress() throws Exception {
        try(var store=open(new Persistence())){store.createTopic(TOPIC,1);store.append(PART,Optional.empty(),EMPTY,false);
            try(var handle=store.snapshot(PART).acquire()) {
                for(int i=1;i<48;i++){store.append(PART,Optional.empty(),EMPTY,false);store.maintain(PART);}
                assertEquals(45,store.snapshot(PART).start);assertEquals(15,store.quota.view().pendingDeletion());
                assertEquals(StorageException.Code.LIMIT,assertThrows(StorageException.class,()->store.append(PART,Optional.empty(),EMPTY,false)).code());
                assertEquals(1,handle.read(0,1,0).records().size());assertThrows(IllegalArgumentException.class,()->handle.read(2,1,0));
            }
            while(store.maintain(PART).again()) { /* each turn deletes at most four */ }
            assertEquals(48,store.append(PART,Optional.empty(),EMPTY,false).offset());assertEquals(0,store.quota.view().pendingDeletion());
        }
    }

}
