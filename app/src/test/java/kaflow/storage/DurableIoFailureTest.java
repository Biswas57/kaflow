package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class DurableIoFailureTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings={".log", ".pub.tmp"})
    void diskFullAfterPartialWriteRetainsOnlyTrustworthyPublishedRange(String suffix) throws Exception {
        var io = new StorageTestSupport.ControlledIo(); var disk = new Persistence(io);
        try(var store = DurableStore.open(directory, OPTIONS, CLOCK, disk)) {
            store.createTopic(TOPIC,1); store.append(PART,Optional.empty(),EMPTY,true);
            io.targetSuffix=suffix; io.failAfter=5; io.armed=true;
            assertSame(io.injected,assertThrows(IOException.class,()->store.append(PART,Optional.empty(),EMPTY,true)));
            assertThrows(IOException.class,()->store.append(PART,Optional.empty(),EMPTY,true));
        }
        assertEquals(0,io.live);
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {
            assertEquals(1,store.recovery().ends().get(PART)); assertEquals(1,store.append(PART,Optional.empty(),EMPTY,true).offset());
        }
    }

    @Test void diskFullDuringRotationHeaderKeepsAcknowledgedSegmentAndCanRecover() throws Exception {
        var small=options(74,16,1000); var io=new StorageTestSupport.ControlledIo();
        try(var store=DurableStore.open(directory,small,CLOCK,new Persistence(io))) {
            store.createTopic(TOPIC,1); store.append(PART,Optional.empty(),EMPTY,true);
            io.targetSuffix=".log";io.failAfter=5;io.armed=true;
            assertSame(io.injected,assertThrows(IOException.class,()->store.append(PART,Optional.empty(),EMPTY,true)));
        }
        assertEquals(0,io.live);
        try(var store=DurableStore.open(directory,small,CLOCK,new Persistence())) {
            assertEquals(1,store.recovery().ends().get(PART));assertEquals(1,store.append(PART,Optional.empty(),EMPTY,true).offset());
        }
    }

    @Test void actualForceFailureLatchesWriterAndInitializationCleanupReleasesOwnership() throws Exception {
        boolean[] armed={false}; StorageIo real=StorageIo.system(); IOException failure=new IOException("force failed");
        StorageIo io=(path,mode)->{
            var f=real.open(path,mode);
            return new StorageIo.File(){
                public int read(ByteBuffer b,long p)throws IOException{return f.read(b,p);}
                public int write(ByteBuffer b,long p)throws IOException{return f.write(b,p);}
                public long size()throws IOException{return f.size();}
                public void force()throws IOException{if(armed[0])throw failure; f.force();}
                public void close()throws IOException{f.close();}
            };
        };
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence(io))) {
            store.createTopic(TOPIC,1); armed[0]=true;
            assertSame(failure,assertThrows(IOException.class,()->store.append(PART,Optional.empty(),EMPTY,true)));
        }
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())){assertEquals(0,store.recovery().ends().get(PART));}
        var io2=new StorageTestSupport.ControlledIo(); io2.armed=true; io2.zeroReads=true;
        assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence(io2)));
        assertEquals(0,io2.live);
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())){assertEquals(0,store.recovery().ends().get(PART));}
    }

    @ParameterizedTest @ValueSource(strings={"length", "version", "uuid", "partition", "generation", "end", "base", "position", "unkeyed"})
    void checksummedButInvalidCheckpointIsNotTrusted(String field) throws Exception {
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())){store.createTopic(TOPIC,1);store.append(PART,Optional.empty(),EMPTY,true);}
        byte[] bytes=Files.readAllBytes(checkpoint(directory)); ByteBuffer b=ByteBuffer.wrap(bytes);
        switch(field){
            case "length"->b.putInt(8,Integer.MAX_VALUE);
            case "version"->b.putShort(4,(short)2);
            case "uuid"->b.putLong(12,b.getLong(12)^1);
            case "partition"->b.putInt(32,1);
            case "generation"->b.putLong(40,2);
            case "end"->b.putLong(48,-1);
            case "base"->b.putLong(56,1);
            case "position"->b.putLong(64,Long.MAX_VALUE);
            case "unkeyed"->b.putLong(72,2);
        }
        StorageTestSupport.crc(bytes); Files.write(checkpoint(directory),bytes);
        byte[] before=Files.readAllBytes(log(directory));
        assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));
        assertArrayEquals(bytes,Files.readAllBytes(checkpoint(directory))); assertArrayEquals(before,Files.readAllBytes(log(directory)));
    }

    @Test void ordinaryPathAliasesCannotTakeRootOwnership() throws Exception {
        Path root=directory.resolve("data"),alias=directory.resolve("alias");
        try(var store=DurableStore.open(root,OPTIONS,CLOCK,new Persistence())) {
            Files.createSymbolicLink(alias,root);
            var error=assertThrows(StorageException.class,()->DurableStore.open(alias,OPTIONS,CLOCK,new Persistence()));
            assertEquals(StorageException.Code.OWNERSHIP,error.code());
        }
        try(var store=DurableStore.open(alias,OPTIONS,CLOCK,new Persistence())){assertTrue(store.recovery().topics().isEmpty());}
    }

    @Test void recoveryDeletionFailureCanRepeatWithoutLosingPublishedBytes() throws Exception {
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())){store.createTopic(TOPIC,1);store.append(PART,Optional.empty(),EMPTY,true);}
        Path tail=log(directory).resolveSibling("00000000000000000001.log"); Files.write(tail,new byte[]{1,2});
        Events disk=new Events();disk.fail="recovery-deleted";
        assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,disk));
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())){assertEquals(1,store.recovery().ends().get(PART));}
    }
}
