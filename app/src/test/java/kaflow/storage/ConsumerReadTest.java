package kaflow.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.broker.*;
import kaflow.consumer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.ConsumerTestSupport.*;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.DurableException.Code.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ConsumerReadTest {
    @TempDir Path directory;
    @Test void exactBinaryRecordsBoundsContinuationAndReplayNeverCommit() throws Exception {
        try (var broker = ConsumerTestSupport.start(directory, new AtomicLong(), new Persistence())) {
            await(broker.createTopic(TOPIC,1));
            byte[] binary={0,-1,-128,-61,40};
            var first=await(broker.append(new AppendCommand(TOPIC,OptionalInt.of(0),Optional.of(EMPTY),ImmutableBytes.copyOf(binary))));
            await(broker.append(command()));await(broker.append(command((byte)7)));
            var member=await(broker.join(JOIN));assertEquals(List.of(0L),member.committedOffsets());
            var batch=await(broker.fetch(fetch(member,0,128,5)));
            assertEquals(2,batch.records().size());assertEquals(2,batch.nextOffset());assertEquals(3,batch.publishedEnd());
            var record=batch.records().get(0);assertEquals(PART,record.partition());assertEquals(first.offset(),record.offset());
            assertEquals(first.timestampMillis(),record.timestampMillis());assertArrayEquals(binary,record.payload().toByteArray());
            assertTrue(record.key().isPresent());assertEquals(0,record.key().get().length());assertTrue(batch.records().get(1).key().isEmpty());
            byte[] copy=record.payload().toByteArray();copy[0]=8;assertArrayEquals(binary,record.payload().toByteArray());
            assertThrows(UnsupportedOperationException.class,()->batch.records().clear());
            assertEquals(1,await(broker.fetch(fetch(member,1,1,0))).records().size());
            failure(RESOURCE_EXHAUSTED,broker.fetch(fetch(member,0,1,4)));
            var end=await(broker.fetch(fetch(member,3,1,0)));assertTrue(end.records().isEmpty());assertEquals(3,end.nextOffset());
            failure(OUT_OF_RANGE,broker.fetch(fetch(member,4,1,0)));
            failure(INVALID_ARGUMENT,broker.fetch(fetch(member,-1,1,0)));
            failure(INVALID_ARGUMENT,broker.fetch(fetch(member,0,0,0)));
            failure(INVALID_ARGUMENT,broker.fetch(fetch(member,0,129,0)));
            failure(INVALID_ARGUMENT,broker.fetch(fetch(member,0,1,-1)));
            failure(INVALID_ARGUMENT,broker.fetch(fetch(member,0,1,2097153)));
            assertEquals(List.of(0L),await(broker.heartbeat(member.identity())).committedOffsets());
            assertEquals(3,await(broker.commit(commit(member,3))));
            assertEquals(0,await(broker.fetch(fetch(member,0,1,5))).records().get(0).offset());
            assertEquals(List.of(3L),await(broker.heartbeat(member.identity())).committedOffsets());
        }
    }
    @Test void capturedBoundaryAndIndependentReadersAcrossRotatingSegments() throws Exception {
        Events disk=new Events();
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<8;i++)await(broker.append(command((byte)i)));
            var member=await(broker.join(JOIN));disk.pause="fetch-read-started";
            var read=broker.fetch(fetch(member,1,128,128));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            for(int i=8;i<12;i++)await(broker.append(command((byte)i)));
            assertEquals(1,await(broker.heartbeat(member.identity())).generation());disk.proceed.countDown();
            var batch=await(read);assertEquals(8,batch.publishedEnd());assertEquals(8,batch.nextOffset());assertEquals(7,batch.records().size());
            for(int i=0;i<7;i++){assertEquals(i+1,batch.records().get(i).offset());assertArrayEquals(new byte[]{(byte)(i+1)},batch.records().get(i).payload().toByteArray());}
            assertEquals(12,await(broker.fetch(fetch(member,8,128,128))).nextOffset());
        } finally {disk.proceed.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"join","leave","expiry","cancel","close"})
    void blockedReadCannotAuthorizeAfterOwnershipLossOrCancellation(String action) throws Exception {
        Events disk=new Events();AtomicLong time=new AtomicLong();
        var broker=ConsumerTestSupport.start(directory,time,disk);
        try {
            await(broker.createTopic(TOPIC,1));await(broker.append(command()));var member=await(broker.join(JOIN));
            disk.pause="fetch-read-complete";var read=broker.fetch(fetch(member,0,1,0));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            switch(action) {
                case "join" -> await(broker.join(JOIN));
                case "leave" -> await(broker.leave(member.identity()));
                case "expiry" -> {time.set(100_000_000);failure(FENCED,broker.heartbeat(member.identity()));}
                case "cancel" -> assertTrue(read.cancel(false));
                case "close" -> {assertFalse(broker.shutdown(Duration.ofMillis(20)));assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));}
            }
            assertEquals(1,broker.consumerDiagnostics().reads());disk.proceed.countDown();
            if(action.equals("cancel"))assertTrue(read.isCancelled());else failure(action.equals("close")?UNAVAILABLE:FENCED,read);
            broker.awaitIdle(WAIT);assertEquals(0,broker.consumerDiagnostics().reads());assertEquals(0,broker.consumerDiagnostics().readBytes());
        } finally {disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));}
    }
    @Test void corruptionIsNotHiddenByAPartialBatchAndDegradesBroker() throws Exception {
        Events disk=new Events();
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));await(broker.append(command()));await(broker.append(command((byte)1)));
            var member=await(broker.join(JOIN));disk.pause="fetch-read-started";var read=broker.fetch(fetch(member,0,2,128));
            assertTrue(disk.entered.await(5,TimeUnit.SECONDS));var path=log(directory);byte[] bytes=Files.readAllBytes(path);bytes[bytes.length-1]^=1;Files.write(path,bytes);
            disk.proceed.countDown();failure(DATA_LOSS,read);assertEquals(DurableBroker.State.DEGRADED,broker.diagnostics().state());
            failure(UNAVAILABLE,broker.append(command()));assertEquals(1,await(broker.heartbeat(member.identity())).generation());
        } finally {disk.proceed.countDown();}
    }
    @Test void boundedReadPoolQueueAndCountAdmissionKeepHeartbeatsResponsive() throws Exception {
        Events disk=new Events();var limits=new ConsumerOptions(1,2,64L*1024*1024,2,4096);
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),disk,limits)) {
            await(broker.createTopic(TOPIC,1));var member=await(broker.join(JOIN));disk.pause="fetch-read-started";
            var a=broker.fetch(fetch(member,0,1,0));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));var b=broker.fetch(fetch(member,0,1,0));
            failure(RESOURCE_EXHAUSTED,broker.fetch(fetch(member,0,1,0)));assertEquals(1,await(broker.heartbeat(member.identity())).generation());
            assertEquals(2,broker.consumerDiagnostics().reads());assertTrue(broker.consumerDiagnostics().queuedReads()<=1);
            disk.proceed.countDown();assertTrue(await(a).records().isEmpty());assertTrue(await(b).records().isEmpty());broker.awaitIdle(WAIT);
            assertEquals(0,broker.consumerDiagnostics().readBytes());
        } finally {disk.proceed.countDown();}
    }
    @Test void readByteAdmissionAndDeliveryReservationAreIndependent() throws Exception {
        var limits=new ConsumerOptions(1,2,0,2,4096);
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence(),limits)) {
            await(broker.createTopic(TOPIC,1));var member=await(broker.join(JOIN));failure(RESOURCE_EXHAUSTED,broker.fetch(fetch(member,0,1,0)));
            assertEquals(0,broker.consumerDiagnostics().reads());
        }
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence(),new ConsumerOptions(1,1,64L*1024*1024,2,4096))) {
            var member=await(broker.join(JOIN));var read=broker.fetch(fetch(member,0,1,0),(value,error)->{entered.countDown();try{barrier(release);}catch(IOException e){throw new RuntimeException(e);}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));failure(RESOURCE_EXHAUSTED,broker.fetch(fetch(member,0,1,0)));
            assertEquals(1,await(broker.heartbeat(member.identity())).generation());release.countDown();await(read);broker.awaitIdle(WAIT);
            assertEquals(0,broker.consumerDiagnostics().reads());
        } finally {release.countDown();}
    }
    @Test void readWatchdogAndShutdownRetainOwnershipUntilActualUnwind() throws Exception {
        Events disk=new Events();AtomicLong time=new AtomicLong();var broker=ConsumerTestSupport.start(directory,time,disk);
        try {
            await(broker.createTopic(TOPIC,1));var member=await(broker.join(JOIN));disk.pause="fetch-read-started";
            var read=broker.fetch(fetch(member,0,1,0));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));time.set(Duration.ofSeconds(31).toNanos());broker.pulse();
            failure(UNAVAILABLE,read);assertEquals(1,broker.consumerDiagnostics().reads());assertEquals(DurableBroker.State.DEGRADED,broker.diagnostics().state());
            assertFalse(broker.shutdown(Duration.ofMillis(20)));assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));
            disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));assertEquals(0,broker.consumerDiagnostics().reads());
        } finally {disk.proceed.countDown();broker.shutdown(WAIT);}
    }
}
