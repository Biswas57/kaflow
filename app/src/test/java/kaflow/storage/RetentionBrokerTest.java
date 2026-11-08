package kaflow.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.consumer.*;
import kaflow.groups.*;
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

@Timeout(40)
class RetentionBrokerTest {
    @TempDir Path directory;
    DurableBroker start(Persistence disk, java.util.function.Consumer<String> observer) throws Exception {
        var consumer=new ConsumerOptions(1,4,64L<<20,16,1L<<20);
        var b=DurableBroker.start(directory,OPTIONS,GroupOptions.defaults(),consumer,WaitOptions.defaults(),RetentionStoreTest.RETAIN,CLOCK,new AtomicLong()::get,disk,observer);
        b.awaitReady(WAIT);return b;
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void queuedAndCancelledReadsKeepPinsUntilActualWorkEnds(boolean cancel) throws Exception {
        Events disk=new Events();CountDownLatch dispatched=new CountDownLatch(2);
        var broker=start(disk,e->{if(e.equals("fetch-dispatched"))dispatched.countDown();});
        try {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command((byte)i)));
            var member=await(broker.join(JOIN));disk.pause="fetch-read-started";
            var first=broker.fetch(fetch(member,0,3,10));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            var queued=broker.fetch(fetch(member,0,3,10));assertTrue(dispatched.await(5,TimeUnit.SECONDS));
            assertEquals(2,broker.lifecycleDiagnostics().segmentPins());assertEquals(1,broker.consumerDiagnostics().queuedReads());
            for(int i=3;i<10;i++)await(broker.append(command((byte)i)));
            broker.awaitRetention(PART,9,3,WAIT);assertTrue(Files.exists(log(directory)));
            assertEquals(2,broker.lifecycleDiagnostics().segmentPins());failure(OUT_OF_RANGE,broker.fetch(fetch(member,0,1,10)));
            if(cancel)assertTrue(first.cancel(false));
            assertEquals(2,broker.lifecycleDiagnostics().segmentPins());assertEquals(0,await(broker.heartbeat(member.identity())).committedOffsets().get(0));
            disk.proceed.countDown();if(!cancel){var result=await(first);assertEquals(3,result.nextOffset());assertEquals(3,result.publishedEnd());}
            var result=await(queued);assertEquals(3,result.records().size());for(int i=0;i<3;i++)assertArrayEquals(new byte[]{(byte)i},result.records().get(i).payload().toByteArray());
            broker.awaitIdle(WAIT);broker.awaitRetention(PART,9,0,WAIT);assertFalse(Files.exists(log(directory)));assertEquals(0,broker.lifecycleDiagnostics().segmentPins());
        } finally {disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));}
    }
    @Test void rangesIndependentLaggingGroupsReplayAndExplicitResetSurviveRestart() throws Exception {
        var other=new JoinCommand(new GroupId("independent"),TOPIC,"other");
        try(var broker=start(new Persistence(),e->{})) {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command((byte)i)));
            var a=await(broker.join(JOIN));var b=await(broker.join(other));await(broker.commit(commit(a,1)));await(broker.commit(commit(b,2)));
            for(int i=3;i<10;i++)await(broker.append(command((byte)i)));broker.awaitRetention(PART,9,0,WAIT);
            var view=await(broker.heartbeat(a.identity()));assertEquals(List.of(1L),view.committedOffsets());assertEquals(List.of(9L),view.retainedStarts());assertEquals(List.of(10L),view.publishedEnds());
            assertEquals(List.of(2L),await(broker.heartbeat(b.identity())).committedOffsets());
            failure(OUT_OF_RANGE,broker.fetch(fetch(a,8,1,10)));failure(OUT_OF_RANGE,broker.fetch(fetch(a,11,1,10)));
            assertEquals(9,await(broker.fetch(fetch(a,9,1,10))).records().get(0).offset());assertEquals(10,await(broker.fetch(fetch(a,10,1,10))).nextOffset());
            assertEquals(1,await(broker.commit(commit(a,0))));assertEquals(1,await(broker.commit(commit(a,1))));failure(OUT_OF_RANGE,broker.commit(commit(a,2)));
            failure(FENCED,broker.reset(GROUP,PART,9));await(broker.leave(a.identity()));failure(OUT_OF_RANGE,broker.reset(GROUP,PART,8));assertEquals(9,await(broker.reset(GROUP,PART,9)));
            assertEquals(List.of(9L),await(broker.join(JOIN)).committedOffsets());
        }
        try(var broker=start(new Persistence(),e->{})) {assertEquals(List.of(9L),await(broker.join(JOIN)).committedOffsets());var b=await(broker.join(other));assertEquals(List.of(2L),b.committedOffsets());assertEquals(List.of(9L),b.retainedStarts());assertEquals(10,await(broker.append(command())).offset());}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void queuedProgressChecksRetainedFloorAtApplication(boolean reset) throws Exception {
        Events disk=new Events();CountDownLatch queued=new CountDownLatch(1);
        var broker=start(disk,e->{if(e.equals("progress-queued"))queued.countDown();});
        try {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command()));var m=await(broker.join(JOIN));
            if(reset)await(broker.leave(m.identity()));disk.pause="retention-written";
            await(broker.append(command()));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            var operation=reset?broker.reset(GROUP,PART,1):broker.commit(commit(m,1));assertTrue(queued.await(5,TimeUnit.SECONDS));assertEquals(0,broker.consumerDiagnostics().barriers());
            disk.proceed.countDown();failure(OUT_OF_RANGE,operation);broker.awaitRetention(PART,3,0,WAIT);
            var view=reset?await(broker.join(JOIN)):await(broker.heartbeat(m.identity()));assertEquals(List.of(0L),view.committedOffsets());
        } finally {disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));}
    }
    @Test void parkedFetchHasNoPinsAndWakesAtRetainedActiveRecord() throws Exception {
        try(var broker=start(new Persistence(),e->{})) {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command()));var m=await(broker.join(JOIN));
            var waiting=broker.fetch(new FetchCommand(m.identity(),m.generation(),PART,3,1,10,10000));broker.awaitParked(1,WAIT);
            assertEquals(0,broker.lifecycleDiagnostics().segmentPins());assertEquals(0,broker.consumerDiagnostics().reads());
            var receipt=await(broker.append(command((byte)-1)));var result=await(waiting);assertEquals(receipt.offset(),result.records().get(0).offset());assertArrayEquals(new byte[]{-1},result.records().get(0).payload().toByteArray());
            broker.awaitRetention(PART,3,0,WAIT);assertEquals(List.of(0L),await(broker.heartbeat(m.identity())).committedOffsets());
        }
    }
    @Test void reassignmentStillFencesReadCapturedBeforeRetention() throws Exception {
        Events disk=new Events();var broker=start(disk,e->{});
        try {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command()));var m=await(broker.join(JOIN));disk.pause="fetch-read-started";
            var read=broker.fetch(fetch(m,0,3,10));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));await(broker.append(command()));broker.awaitRetention(PART,3,1,WAIT);
            await(broker.join(JOIN));disk.proceed.countDown();failure(FENCED,read);broker.awaitRetention(PART,3,0,WAIT);
        } finally {disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));}
    }
    @Test void fullAppendIngressCannotStarveControlsCancellationOrMaintenanceCompletion() throws Exception {
        Events disk=new Events();var broker=start(disk,e->{});
        try {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command()));var m=await(broker.join(JOIN));
            disk.pause="retention-delete-before";await(broker.append(command()));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            var queued=new java.util.ArrayList<java.util.concurrent.CompletableFuture<DurableReceipt>>();
            for(int i=0;i<OPTIONS.maxCalls();i++)queued.add(broker.append(command()));
            failure(RESOURCE_EXHAUSTED,broker.append(command()));assertEquals(OPTIONS.maxCalls(),broker.diagnostics().queuedCalls());
            assertEquals(List.of(3L),await(broker.heartbeat(m.identity())).retainedStarts());
            var waiter=broker.fetch(new FetchCommand(m.identity(),m.generation(),PART,4,1,10,10000));broker.awaitParked(1,WAIT);
            assertTrue(waiter.cancel(false));broker.awaitParked(0,WAIT);assertEquals(0,broker.lifecycleDiagnostics().segmentPins());
            disk.proceed.countDown();for(int i=0;i<queued.size();i++)assertEquals(4+i,await(queued.get(i)).offset());
            broker.awaitIdle(WAIT);assertEquals(DurableBroker.State.READY,broker.diagnostics().state());assertEquals(0,broker.waitDiagnostics().admitted());
        } finally {disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));}
    }
    @Test void resetJoinBarrierStillOrdersActivationAfterRetention() throws Exception {
        Events disk=new Events();var broker=start(disk,e->{});
        try {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<4;i++)await(broker.append(command()));broker.awaitRetention(PART,3,0,WAIT);
            var m=await(broker.join(JOIN));await(broker.leave(m.identity()));disk.pause="commit-forced";
            var reset=broker.reset(GROUP,PART,3);assertTrue(disk.entered.await(5,TimeUnit.SECONDS));failure(UNAVAILABLE,broker.join(JOIN));
            assertEquals(1,broker.consumerDiagnostics().barriers());disk.proceed.countDown();assertEquals(3,await(reset));
            var next=await(broker.join(JOIN));assertEquals(List.of(3L),next.committedOffsets());assertEquals(List.of(3L),next.retainedStarts());
        } finally {disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));}

    }
}
