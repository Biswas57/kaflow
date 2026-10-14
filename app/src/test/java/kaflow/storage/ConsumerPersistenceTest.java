package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import kaflow.groups.GroupId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.ConsumerTestSupport.GROUP;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ConsumerPersistenceTest {
    @TempDir Path directory;
    private Path progress() { return directory.resolve("progress/g-00000000.commit"); }
    private void seed(DurableStore store) throws Exception {
        store.createTopic(TOPIC,1);store.append(PART,Optional.empty(),EMPTY,false);store.append(PART,Optional.empty(),EMPTY,false);store.createGroup(GROUP,TOPIC);
    }
    @Test void upgradeExactFormatIdempotencyBoundedCostAndNewGroups() throws Exception {
        Events disk=new Events();byte[] v2;
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {seed(store);v2=Files.readAllBytes(directory.resolve("catalog"));}
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {
            assertArrayEquals(v2,Files.readAllBytes(directory.resolve("catalog")));assertFalse(Files.exists(progress()));
            assertEquals(0,store.commit(GROUP,PART,0,false));assertFalse(Files.exists(progress()));
            assertEquals(1,store.commit(GROUP,PART,1,false));var catalog=Files.readAllBytes(directory.resolve("catalog"));assertEquals(3,ByteBuffer.wrap(catalog).getShort(4));
            byte[] expected=new byte[60];ByteBuffer b=ByteBuffer.wrap(expected);b.putInt(0x4b46434f).putShort((short)1).putShort((short)0).putInt(60);
            b.put(catalog,12,16).putInt(0).putInt(0).putInt(1).putLong(1).putLong(1);
            assertArrayEquals(StorageTestSupport.crc(expected),Files.readAllBytes(progress()));
            assertEquals(1,store.commit(GROUP,PART,0,false));assertEquals(1,store.commit(GROUP,PART,1,false));
            assertArrayEquals(StorageTestSupport.crc(expected),Files.readAllBytes(progress()));
            var other=new GroupId("second");store.createGroup(other,TOPIC);var otherBytes=Files.readAllBytes(directory.resolve("progress/g-00000001.commit"));
            catalog=Files.readAllBytes(directory.resolve("catalog"));assertEquals(2,store.commit(GROUP,PART,2,false));
            assertArrayEquals(catalog,Files.readAllBytes(directory.resolve("catalog")));assertArrayEquals(otherBytes,Files.readAllBytes(directory.resolve("progress/g-00000001.commit")));
            store.createTopic(new kaflow.broker.TopicId("later"),1);
        }
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            assertEquals(List.of(2L),store.recovery().progress().get(GROUP));assertEquals(List.of(0L),store.recovery().progress().get(new GroupId("second")));
            assertEquals(1,store.commit(GROUP,PART,1,true));
        }
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {assertEquals(List.of(1L),store.recovery().progress().get(GROUP));}
    }
    @ParameterizedTest @ValueSource(strings={"missing","directory","checksum","length","version","flags","store","group","topic","count","revision","negative","beyond","zero-revision"})
    void corruptOrMissingProgressNeverResetsOrRepairsAuthority(String kind) throws Exception {
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {seed(store);store.commit(GROUP,PART,1,false);}
        byte[] logBefore=Files.readAllBytes(log(directory));
        if(kind.equals("missing"))Files.delete(progress());
        else if(kind.equals("directory")){Files.delete(progress());Files.delete(directory.resolve("progress"));}
        else {
            byte[] data=Files.readAllBytes(progress());ByteBuffer b=ByteBuffer.wrap(data);
            switch(kind) {
                case "checksum" -> data[data.length-1]^=1;
                case "length" -> b.putInt(8,61);
                case "version" -> b.putShort(4,(short)2);
                case "flags" -> b.putShort(6,(short)1);
                case "store" -> b.putLong(12,999);
                case "group" -> b.putInt(28,1);
                case "topic" -> b.putInt(32,1);
                case "count" -> b.putInt(36,2);
                case "revision" -> b.putLong(40,-1);
                case "negative" -> b.putLong(48,-1);
                case "beyond" -> b.putLong(48,3);
                case "zero-revision" -> b.putLong(40,0);
            }
            Files.write(progress(),kind.equals("checksum")?data:StorageTestSupport.crc(data));
        }
        for(int i=0;i<2;i++)assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));
        assertArrayEquals(logBefore,Files.readAllBytes(log(directory)));
    }
    @ParameterizedTest @ValueSource(strings={"progress-initial-written","progress-initial-forced","progress-initial-replaced","progress-initial-synced","progress-catalog-written","progress-catalog-forced","progress-catalog-replaced","progress-catalog-synced"})
    void interruptedUpgradeRecoversZeroWithoutPromotingScratch(String point) throws Exception {
        Events disk=new Events();
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {seed(store);disk.fail=point;assertThrows(IOException.class,()->store.commit(GROUP,PART,1,false));}
        for(int i=0;i<2;i++)try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {assertEquals(List.of(0L),store.recovery().progress().get(GROUP));}
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {assertEquals(1,store.commit(GROUP,PART,1,false));}
    }
    @ParameterizedTest @ValueSource(strings={"commit-written","commit-forced","commit-replaced","commit-synced"})
    void failedCommitRecoversOldOrNewButNeverLosesPreviouslyAcknowledgedProgress(String point) throws Exception {
        Events disk=new Events();
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {seed(store);store.commit(GROUP,PART,1,false);disk.fail=point;assertThrows(IOException.class,()->store.commit(GROUP,PART,2,false));}
        long expected=point.endsWith("replaced")||point.endsWith("synced")?2:1;
        for(int i=0;i<2;i++)try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {assertEquals(List.of(expected),store.recovery().progress().get(GROUP));}
    }
    @Test void interruptedCleanupAndUnknownScratchVersionPreserveAuthority() throws Exception {
        Events disk=new Events();
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {seed(store);store.commit(GROUP,PART,1,false);disk.fail="commit-forced";assertThrows(IOException.class,()->store.commit(GROUP,PART,2,false));}
        var temp=progress().resolveSibling("g-00000000.commit.tmp");byte[] saved=Files.readAllBytes(temp),bad=saved.clone();ByteBuffer.wrap(bad).putShort(4,(short)2);Files.write(temp,StorageTestSupport.crc(bad));
        assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));Files.write(temp,saved);
        Events cleanup=new Events();cleanup.fail="recovery-deleted";assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,cleanup));
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {assertEquals(List.of(1L),store.recovery().progress().get(GROUP));}
    }
    @Test void newV3GroupOrphanIsCleanedOnlyWhenZeroAndIdentityMatches() throws Exception {
        Events disk=new Events();
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {seed(store);store.commit(GROUP,PART,1,false);disk.fail="group-catalog-forced";assertThrows(IOException.class,()->store.createGroup(new GroupId("orphan"),TOPIC));}
        var orphan=directory.resolve("progress/g-00000001.commit");byte[] zero=Files.readAllBytes(orphan),bad=zero.clone();ByteBuffer.wrap(bad).putLong(40,1).putLong(48,1);Files.write(orphan,StorageTestSupport.crc(bad));
        assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));Files.write(orphan,zero);
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {assertEquals(1,store.recovery().groups().size());assertFalse(Files.exists(orphan));}
    }
    @Test void revisionOverflowRejectsBeforeAnyMutation() throws Exception {
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {seed(store);store.commit(GROUP,PART,1,false);}
        byte[] data=Files.readAllBytes(progress());ByteBuffer.wrap(data).putLong(40,Long.MAX_VALUE);Files.write(progress(),StorageTestSupport.crc(data));
        byte[] before=Files.readAllBytes(progress());
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            assertEquals(StorageException.Code.LIMIT,assertThrows(StorageException.class,()->store.commit(GROUP,PART,2,false)).code());
            assertEquals(1,store.commit(GROUP,PART,1,false));
        }
        assertArrayEquals(before,Files.readAllBytes(progress()));
    }
}
