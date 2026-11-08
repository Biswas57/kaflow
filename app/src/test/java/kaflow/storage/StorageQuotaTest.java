package kaflow.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
class StorageQuotaTest {
    @TempDir Path directory;
    final long metadata = LifecycleOptions.defaults().metadataBudget(OPTIONS, GroupOptions.defaults());
    DurableStore open(LifecycleOptions limits, Persistence disk) throws IOException {
        return DurableStore.open(directory, OPTIONS, GroupOptions.defaults(), limits, CLOCK, disk);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exactFramingAndRotationCeilingRejectWithoutGapOrDegradation(boolean partitionLimit) throws Exception {
        var limits = new LifecycleOptions(0, metadata + (partitionLimit ? 2000 : 904), partitionLimit ? 904 : 2000);
        try(var store=open(limits,new Persistence())) {
            store.createTopic(TOPIC,1); assertEquals(metadata+70,store.quota.view().accountedBytes());
            for(int i=0;i<12;i++)assertEquals(i,store.append(PART,Optional.empty(),EMPTY,false).offset());
            assertEquals(metadata+904,store.quota.view().accountedBytes());
            assertEquals(StorageException.Code.LIMIT,assertThrows(StorageException.class,()->store.append(PART,Optional.empty(),EMPTY,false)).code());
            assertEquals(12,store.snapshot(PART).end); assertEquals(0,store.quota.view().reservedBytes());
            store.maintain(PART); assertEquals(0,store.snapshot(PART).start); // Quota never enables retention.
            var group=new GroupId("full");store.createGroup(group,TOPIC);assertEquals(12,store.commit(group,PART,12,false));
            assertTrue(Files.exists(directory.resolve("progress/g-00000000.commit")), "metadata headroom remains usable");
            long actual;try(var paths=Files.walk(directory)){actual=paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new RuntimeException(e);}}).sum();}
            assertTrue(actual<=store.quota.view().accountedBytes());
        }
        try(var store=open(limits,new Persistence())){assertEquals(metadata+904,store.quota.view().accountedBytes());assertEquals(12,store.snapshot(PART).end);}
    }
    @Test void logicalExpiryAndPinsDoNotCreditStorageButConfirmedDeletionDoes() throws Exception {
        var limits=new LifecycleOptions(200,metadata+1000,1000);
        try(var store=open(limits,new Persistence())) {
            store.createTopic(TOPIC,1);for(int i=0;i<12;i++)store.append(PART,Optional.empty(),EMPTY,false);
            var handle=store.snapshot(PART).acquire();
            try {
                store.maintain(PART);assertEquals(9,store.snapshot(PART).start);assertEquals(3,store.quota.view().pendingDeletion());
                assertEquals(metadata+904,store.quota.view().accountedBytes());
                assertThrows(StorageException.class,()->store.append(PART,Optional.empty(),EMPTY,false));
                assertTrue(Files.exists(log(directory)));
            } finally {handle.close();handle.close();}
            store.maintain(PART);assertEquals(metadata+226,store.quota.view().accountedBytes());
            assertEquals(12,store.append(PART,Optional.empty(),EMPTY,false).offset());assertEquals(metadata+356,store.quota.view().accountedBytes());
        }
    }
    @Test void pendingGrowthIsReservedThroughForceAndAtomicReplacementOverlap() throws Exception {
        var limits=new LifecycleOptions(0,metadata+1000,1000);
        final DurableStore[] current=new DurableStore[1];
        Persistence disk=new Persistence(){@Override void event(String point)throws IOException{
            if(current[0]!=null&&(point.equals("data-written")||point.equals("checkpoint-written")||point.equals("checkpoint-replaced"))) {
                assertEquals(60,current[0].quota.view().reservedBytes());
                assertEquals(metadata+70,current[0].quota.view().accountedBytes());
                if(point.equals("checkpoint-written"))assertTrue(Files.exists(checkpoint(directory).resolveSibling("p-00000000.pub.tmp")));
            }
        }};
        try(var store=open(limits,disk)){store.createTopic(TOPIC,1);current[0]=store;store.append(PART,Optional.empty(),EMPTY,false);assertEquals(0,store.quota.view().reservedBytes());assertEquals(metadata+130,store.quota.view().accountedBytes());}
    }
    @ParameterizedTest @ValueSource(strings={"data-written","checkpoint-written","retention-written","retention-delete-before"})
    void diskFullNeverAcknowledgesAndRequiresRecovery(String point) throws Exception {
        var limits=new LifecycleOptions(200,metadata+2000,2000);
        Events disk=new Events();
        try(var store=open(limits,disk)) {
            store.createTopic(TOPIC,1);for(int i=0;i<10;i++)store.append(PART,Optional.empty(),EMPTY,false);
            long before=store.quota.view().accountedBytes();disk.fail=point;
            assertSame(disk.injected,assertThrows(IOException.class,()->{if(point.startsWith("retention"))store.maintain(PART);else store.append(PART,Optional.empty(),EMPTY,false);}));
            assertThrows(IOException.class,()->store.append(PART,Optional.empty(),EMPTY,false));assertEquals(0,store.quota.view().reservedBytes());
            assertTrue(store.quota.view().accountedBytes()>=before);
        }
        try(var store=open(limits,new Persistence())){assertEquals(10,store.snapshot(PART).end);}
    }
    @Test void immutableConfigurationAndRecoveryRejectImpossibleLimits() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new LifecycleOptions(-1,1000,1000));
        assertThrows(IllegalArgumentException.class,()->new LifecycleOptions(1001,1000,1000));
        assertThrows(IllegalArgumentException.class,()->new LifecycleOptions(0,Long.MAX_VALUE,1000));
        assertThrows(IllegalArgumentException.class,()->open(new LifecycleOptions(0,1000,1000),new Persistence()));
        try(var store=open(new LifecycleOptions(0,metadata+2000,2000),new Persistence())) {store.createTopic(TOPIC,1);for(int i=0;i<15;i++)store.append(PART,Optional.empty(),EMPTY,false);}
        assertEquals(StorageException.Code.LIMIT,assertThrows(StorageException.class,()->open(new LifecycleOptions(0,metadata+1000,1000),new Persistence())).code());
    }
    @ParameterizedTest @ValueSource(strings={"catalog","checkpoint","progress","index"})
    void oversizedScratchCannotEscapeConservativeMetadataEnvelope(String kind) throws Exception {
        try(var store=open(new LifecycleOptions(200,metadata+2000,2000),new Persistence())) {
            store.createTopic(TOPIC,1);store.append(PART,Optional.empty(),EMPTY,false);var group=new GroupId("scratch");store.createGroup(group,TOPIC);store.commit(group,PART,1,false);
        }
        Path target;int size;
        switch(kind){
            case "catalog"->{target=directory.resolve("catalog.tmp");size=GroupOptions.defaults().maxCatalogBytes()+1;}
            case "checkpoint"->{target=checkpoint(directory).resolveSibling("p-00000000.pub.tmp");size=348;}
            case "progress"->{target=directory.resolve("progress/g-00000000.commit.tmp");size=117;}
            default->{target=log(directory).resolveSibling(".index-rebuild.tmp");size=105;}
        }
        Files.write(target,new byte[size]);assertThrows(IOException.class,()->open(new LifecycleOptions(200,metadata+2000,2000),new Persistence()));assertEquals(size,Files.size(target));
    }

    @Test void unpublishedMultiPartitionTopicUsesGlobalNotSinglePartitionQuotaDuringRecovery() throws Exception {
        var options=DurableTestSupport.options(400,16,1000);
        long headroom=LifecycleOptions.defaults().metadataBudget(options,GroupOptions.defaults());
        var limits=new LifecycleOptions(0,headroom+5000,1100);var topic=new kaflow.broker.TopicId("t".repeat(255));Events disk=new Events();
        try(var store=DurableStore.open(directory,options,GroupOptions.defaults(),limits,CLOCK,disk)) {
            disk.fail="topic-directories-synced";assertThrows(IOException.class,()->store.createTopic(topic,8));
        }
        try(var store=DurableStore.open(directory,options,GroupOptions.defaults(),limits,CLOCK,new Persistence())) {
            assertTrue(store.recovery().topics().isEmpty());store.createTopic(topic,8);assertEquals(headroom+8*319,store.quota.view().accountedBytes());
        }
    }
    @Test void recoveryRejectsMultipleIndexScratchFilesBeforeAnyCleanup() throws Exception {
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())){store.createTopic(TOPIC,2);}
        Path first=log(directory).resolveSibling(".index-rebuild.tmp"),second=directory.resolve("t-00000000/p-00000001/.index-rebuild.tmp");
        Files.write(first,new byte[1]);Files.write(second,new byte[1]);
        assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));assertTrue(Files.exists(first));assertTrue(Files.exists(second));
    }

}
