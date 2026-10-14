package kaflow.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.consumer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.ConsumerTestSupport.*;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.DurableException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ConsumerReadOwnershipTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"success","read-error","close-error","cancel"})
    void independentChannelsCloseOnEveryWorkerOutcome(String outcome) throws Exception {
        AtomicInteger live=new AtomicInteger(),maximum=new AtomicInteger();AtomicBoolean armed=new AtomicBoolean();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        StorageIo io=(path,mode)->{
            var delegate=StorageIo.system().open(path,mode);boolean reader=Thread.currentThread().getName().equals("kaflow-reader");
            if(reader)maximum.accumulateAndGet(live.incrementAndGet(),Math::max);
            return new StorageIo.File(){
                public int read(ByteBuffer buffer,long position)throws IOException{
                    if(reader&&armed.get()){entered.countDown();barrier(release);if(outcome.equals("read-error"))throw new IOException("injected read");}
                    // Exercise independent positional short transfers.
                    int limit=buffer.limit();buffer.limit(Math.min(limit,buffer.position()+3));try{return delegate.read(buffer,position);}finally{buffer.limit(limit);}
                }
                public int write(ByteBuffer b,long p)throws IOException{return delegate.write(b,p);}
                public long size()throws IOException{return delegate.size();}public void force()throws IOException{delegate.force();}
                public void close()throws IOException{try{delegate.close();}finally{if(reader)live.decrementAndGet();}if(reader&&outcome.equals("close-error"))throw new IOException("injected close");}
            };
        };
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence(io))) {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<8;i++)await(broker.append(command((byte)i)));var member=await(broker.join(JOIN));armed.set(true);
            var read=broker.fetch(fetch(member,1,128,128));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(2,live.get());
            await(broker.append(command((byte)8)));assertEquals(1,await(broker.heartbeat(member.identity())).generation());
            if(outcome.equals("cancel"))read.cancel(false);release.countDown();
            if(outcome.endsWith("error"))failure(INTERNAL,read);else if(!outcome.equals("cancel"))assertEquals(8,await(read).nextOffset());
            broker.awaitIdle(WAIT);assertEquals(0,live.get());assertEquals(2,maximum.get());assertEquals(0,broker.consumerDiagnostics().reads());
            if(outcome.endsWith("error"))assertEquals(DurableBroker.State.DEGRADED,broker.diagnostics().state());
        } finally {release.countDown();}
    }
    @Test void multipleReadsAndAppendsShareNoChannelPositionOrMutableSnapshot() throws Exception {
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<6;i++)await(broker.append(command((byte)i)));var member=await(broker.join(JOIN));
            List<CompletableFuture<FetchResult>> reads=new ArrayList<>();List<CompletableFuture<DurableReceipt>> appends=new ArrayList<>();
            for(int i=6;i<14;i++){reads.add(broker.fetch(fetch(member,1,128,128)));appends.add(broker.append(command((byte)i)));}
            for(var append:appends)await(append);
            for(var read:reads){var batch=await(read);assertTrue(batch.publishedEnd()>=6&&batch.publishedEnd()<=14);assertEquals(batch.publishedEnd(),batch.nextOffset());
                for(int i=0;i<batch.records().size();i++){assertEquals(i+1,batch.records().get(i).offset());assertArrayEquals(new byte[]{(byte)(i+1)},batch.records().get(i).payload().toByteArray());}}
            assertEquals(14,await(broker.fetch(fetch(member,0,128,128))).records().size());
        }
    }
    @Test void optionsRejectUnboundedResources() {
        assertThrows(IllegalArgumentException.class,()->new ConsumerOptions(0,1,1,1,1));assertThrows(IllegalArgumentException.class,()->new ConsumerOptions(5,1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new ConsumerOptions(1,129,1,1,1));assertThrows(IllegalArgumentException.class,()->new ConsumerOptions(1,1,268435457,1,1));
        assertThrows(IllegalArgumentException.class,()->new ConsumerOptions(1,1,1,257,1));assertThrows(IllegalArgumentException.class,()->new ConsumerOptions(1,1,1,1,8388609));
    }
}
