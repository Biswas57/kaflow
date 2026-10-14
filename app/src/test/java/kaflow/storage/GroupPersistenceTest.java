package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kaflow.broker.TopicId;
import kaflow.groups.GroupId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.GroupMembershipTest.GROUP;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GroupPersistenceTest {
    @TempDir Path directory;
    @Test void v1OpensUnchangedAndUpgradePreservesTopicsRecordsAndExactBinding() throws Exception {
        byte[] old;
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            store.createTopic(TOPIC,2);store.append(PART,java.util.Optional.empty(),EMPTY,true);
            old=Files.readAllBytes(directory.resolve("catalog"));assertEquals(1,ByteBuffer.wrap(old).getShort(4));
        }
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            assertEquals(List.of(),store.recovery().groups());assertArrayEquals(old,Files.readAllBytes(directory.resolve("catalog")));
            store.createGroup(new GroupId(" café "),TOPIC);
            byte[] upgraded=Files.readAllBytes(directory.resolve("catalog"));assertEquals(2,ByteBuffer.wrap(upgraded).getShort(4));
            store.createGroup(new GroupId(" café "),TOPIC);assertArrayEquals(upgraded,Files.readAllBytes(directory.resolve("catalog")));
            store.createTopic(new TopicId("other"),1);
            assertThrows(IOException.class,()->store.createGroup(new GroupId(" café "),new TopicId("other")));
            assertEquals(1,store.recovery().groups().size());
        }
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            assertEquals(List.of(new DurableMetadata.Group(new GroupId(" café "),TOPIC)),store.recovery().groups());
            assertEquals(2,store.recovery().topics().size());assertEquals(1,store.recovery().ends().get(PART));
        }
    }

    @ParameterizedTest @ValueSource(strings={"group-catalog-written","group-catalog-forced","group-catalog-replaced","group-catalog-synced"})
    void everyPersistenceBoundaryKeepsAnAuthoritativeCatalogAndRepeatableRecovery(String point) throws Exception {
        Events disk=new Events();
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {
            store.createTopic(TOPIC,1);disk.events.clear();disk.fail=point;
            assertSame(disk.injected,assertThrows(IOException.class,()->store.createGroup(GROUP,TOPIC)));
            assertThrows(IOException.class,()->store.createGroup(GROUP,TOPIC));
        }
        for(int i=0;i<2;i++)try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            assertEquals(point.endsWith("replaced")||point.endsWith("synced")?1:0,store.recovery().groups().size());
            assertEquals(0,store.recovery().ends().get(PART));
        }
        assertFalse(Files.exists(directory.resolve("catalog.tmp")));
    }

    @Test void forceReplaceSyncPrecedeDefinitionCompletionAndNoRewriteOnDuplicate() throws Exception {
        Events disk=new Events();
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,disk)) {
            store.createTopic(TOPIC,1);disk.events.clear();store.createGroup(GROUP,TOPIC);
            assertEquals(List.of("group-catalog-written","group-catalog-forced","group-catalog-replaced","group-catalog-synced"),disk.events);
            disk.events.clear();store.createGroup(GROUP,TOPIC);assertTrue(disk.events.isEmpty());
            assertEquals(81,Files.size(directory.resolve("catalog"))); // 48 + (12+6) + (8+7), independently calculated.
        }
    }

    @Test void partialDiskFullPreservesExistingGroupsAndFailedCreationCanBeRetriedAfterRecovery() throws Exception {
        var io=new StorageTestSupport.ControlledIo();
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence(io))) {
            store.createTopic(TOPIC,1);store.createGroup(GROUP,TOPIC);
            io.targetSuffix="catalog.tmp";io.failAfter=9;io.armed=true;
            assertSame(io.injected,assertThrows(IOException.class,()->store.createGroup(new GroupId("new"),TOPIC)));
        }
        assertEquals(0,io.live);
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            assertEquals(List.of(new DurableMetadata.Group(GROUP,TOPIC)),store.recovery().groups());
            store.createGroup(new GroupId("new"),TOPIC);assertEquals(2,store.recovery().groups().size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"missing","checksum","truncated","version","count","generation","length","slot","utf8","blank","duplicate"})
    void damagedAuthoritativeGroupsNeverResetToEmpty(String damage) throws Exception {
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            store.createTopic(TOPIC,1);store.createGroup(GROUP,TOPIC);store.createGroup(new GroupId("another"),TOPIC);
        }
        Path file=directory.resolve("catalog");byte[] bytes=Files.readAllBytes(file);ByteBuffer b=ByteBuffer.wrap(bytes);
        // V2 header 44, topic entry 18, first group starts at 62; seven-byte identifiers.
        switch(damage) {
            case "missing" -> Files.delete(file);
            case "checksum" -> {bytes[bytes.length-1]^=1;Files.write(file,bytes);}
            case "truncated" -> Files.write(file,java.util.Arrays.copyOf(bytes,bytes.length-1));
            default -> {
                switch(damage) {
                    case "version" -> b.putShort(4,(short)3);
                    case "count" -> b.putInt(40,0);
                    case "generation" -> b.putLong(28,99);
                    case "length" -> b.putInt(62,Integer.MAX_VALUE);
                    case "slot" -> b.putInt(66,1);
                    case "utf8" -> bytes[70]=(byte)0xff;
                    case "blank" -> java.util.Arrays.fill(bytes,70,77,(byte)' ');
                    case "duplicate" -> System.arraycopy(bytes,70,bytes,85,7);
                    default -> throw new AssertionError();
                }
                b.putInt(bytes.length-4,LogFormat.checksum(bytes,bytes.length-4));Files.write(file,bytes);
            }
        }
        byte[] before=Files.exists(file)?Files.readAllBytes(file):null;
        assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));
        if(before!=null)assertArrayEquals(before,Files.readAllBytes(file));else assertFalse(Files.exists(file));
    }

    @Test void countAndMetadataByteLimitsRejectWithoutChangingCatalog() throws Exception {
        var limits=GroupMembershipTest.options(1,2,2,1,2,65536,81);
        try(var store=DurableStore.open(directory,OPTIONS,limits,CLOCK,new Persistence())) {
            store.createTopic(TOPIC,1);store.createGroup(GROUP,TOPIC);byte[] catalog=Files.readAllBytes(directory.resolve("catalog"));
            assertEquals(81,catalog.length);
            assertEquals(StorageException.Code.LIMIT,assertThrows(StorageException.class,()->store.createGroup(new GroupId("x"),TOPIC)).code());
            assertEquals(StorageException.Code.LIMIT,assertThrows(StorageException.class,()->store.createTopic(new TopicId("other"),1)).code());
            assertArrayEquals(catalog,Files.readAllBytes(directory.resolve("catalog")));
        }
        Path other=directory.resolve("bytes");var bytesLimit=GroupMembershipTest.options(8,2,2,1,2,65536,80);
        try(var store=DurableStore.open(other,OPTIONS,bytesLimit,CLOCK,new Persistence())) {
            store.createTopic(TOPIC,1);byte[] catalog=Files.readAllBytes(other.resolve("catalog"));
            assertEquals(StorageException.Code.LIMIT,assertThrows(StorageException.class,()->store.createGroup(GROUP,TOPIC)).code());
            assertArrayEquals(catalog,Files.readAllBytes(other.resolve("catalog")));assertEquals(0,store.recovery().groups().size());
        }
    }

    @Test void unknownTemporaryVersionPreventsRepairAndAuthoritativeV2CannotBeMissing() throws Exception {
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {store.createTopic(TOPIC,1);store.createGroup(GROUP,TOPIC);}
        byte[] catalog=Files.readAllBytes(directory.resolve("catalog"));byte[] temp=catalog.clone();ByteBuffer.wrap(temp).putShort(4,(short)3);
        Files.write(directory.resolve("catalog.tmp"),temp);
        assertEquals(StorageException.Code.UNSUPPORTED_VERSION,assertThrows(StorageException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())).code());
        assertArrayEquals(temp,Files.readAllBytes(directory.resolve("catalog.tmp")));assertArrayEquals(catalog,Files.readAllBytes(directory.resolve("catalog")));
    }
}
