package kaflow.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
class ConsumerCommitTest {
    @TempDir Path directory;
    @Test void independentProgressMonotonicityRestartAndReassignmentResume() throws Exception {
        MembershipView old;
        var other=new JoinCommand(new GroupId("consumer-b"),TOPIC,"worker");
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<10;i++)await(broker.append(command((byte)i)));
            old=await(broker.join(JOIN));var b=await(broker.join(other));
            assertEquals(10,await(broker.fetch(fetch(old,0,128,128))).nextOffset());
            assertEquals(List.of(0L),await(broker.heartbeat(old.identity())).committedOffsets());
            assertEquals(10,await(broker.commit(commit(old,10))));assertEquals(10,await(broker.commit(commit(old,10))));
            assertEquals(10,await(broker.commit(commit(old,2))));assertEquals(3,await(broker.commit(commit(b,3))));
            failure(OUT_OF_RANGE,broker.commit(commit(old,11)));failure(INVALID_ARGUMENT,broker.commit(commit(old,-1)));
            assertEquals(List.of(3L),await(broker.heartbeat(b.identity())).committedOffsets());
            var successor=await(broker.join(JOIN));failure(FENCED,broker.commit(commit(old,10)));failure(FENCED,broker.commit(commit(old,0)));
            await(broker.leave(old.identity()));successor=await(broker.heartbeat(successor.identity()));
            assertEquals(List.of(10L),successor.committedOffsets());assertEquals(0,await(broker.fetch(fetch(successor,0,1,1))).records().get(0).offset());
        }
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {
            failure(FENCED,broker.fetch(fetch(old,0,1,1)));failure(FENCED,broker.commit(commit(old,10)));
            var a=await(broker.join(JOIN));var b=await(broker.join(other));assertNotEquals(old.identity().incarnation(),a.identity().incarnation());
            assertEquals(List.of(10L),a.committedOffsets());assertEquals(List.of(3L),b.committedOffsets());
            assertTrue(await(broker.fetch(fetch(a,10,1,1))).records().isEmpty());
            assertEquals(3,await(broker.fetch(fetch(b,3,1,1))).records().get(0).offset());
        }
    }
    @ParameterizedTest @ValueSource(strings={"join","expiry","leave","cancel"})
    void queuedCommitRevalidatesAtWriterReadyWithoutPinning(String transition) throws Exception {
        Events disk=new Events();AtomicLong time=new AtomicLong();CountDownLatch queued=new CountDownLatch(1);
        var broker=DurableBroker.start(directory,OPTIONS,GroupMembershipTest.OPTIONS,CLOCK,time::get,disk,e->{if(e.equals("progress-queued"))queued.countDown();});
        try {
            broker.awaitReady(WAIT);await(broker.createTopic(TOPIC,1));await(broker.append(command()));var member=await(broker.join(JOIN));
            disk.pause="data-forced";var append=broker.append(command());assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            var commit=broker.commit(commit(member,1));assertTrue(queued.await(5,TimeUnit.SECONDS));
            assertEquals(0,broker.consumerDiagnostics().barriers());
            switch(transition) {
                case "join" -> await(broker.join(JOIN));
                case "expiry" -> {time.set(100_000_000);failure(FENCED,broker.heartbeat(member.identity()));}
                case "leave" -> await(broker.leave(member.identity()));
                case "cancel" -> assertTrue(commit.cancel(false));
            }
            disk.proceed.countDown();await(append);
            if(!transition.equals("cancel"))failure(FENCED,commit);broker.awaitIdle(WAIT);
            assertEquals(0,broker.consumerDiagnostics().progressCalls());
        } finally {disk.proceed.countDown();broker.shutdown(WAIT);}
        try(var reopened=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {assertEquals(List.of(0L),await(reopened.join(JOIN)).committedOffsets());}
    }
    @ParameterizedTest @ValueSource(strings={"leave","expiry","cancel","timeout","failure","close"})
    void applyingCommitKeepsGroupBarrierUntilSafeOutcome(String transition) throws Exception {
        Events disk=new Events();AtomicLong time=new AtomicLong();var broker=ConsumerTestSupport.start(directory,time,disk);
        try {
            await(broker.createTopic(TOPIC,1));await(broker.append(command()));var member=await(broker.join(JOIN));
            var other=await(broker.join(new JoinCommand(new GroupId("independent"),TOPIC,"other")));
            disk.pause="commit-forced";if(transition.equals("failure"))disk.fail="commit-forced";
            var commit=broker.commit(commit(member,1));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));assertFalse(commit.isDone());
            failure(UNAVAILABLE,broker.join(JOIN));assertEquals(1,await(broker.heartbeat(other.identity())).generation());
            assertEquals(1,broker.consumerDiagnostics().barriers());
            switch(transition) {
                case "leave" -> {await(broker.leave(member.identity()));failure(FENCED,broker.heartbeat(member.identity()));}
                case "expiry" -> {time.set(100_000_000);failure(FENCED,broker.heartbeat(member.identity()));}
                case "cancel" -> assertTrue(commit.cancel(false));
                case "timeout" -> {time.set(Duration.ofSeconds(31).toNanos());broker.pulse();failure(UNAVAILABLE,commit);}
                case "close" -> assertFalse(broker.shutdown(Duration.ofMillis(20)));
            }
            assertEquals(1,broker.consumerDiagnostics().progressCalls());
            failure(UNAVAILABLE,broker.join(JOIN));assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));
            disk.proceed.countDown();
            if(transition.equals("failure"))failure(INTERNAL,commit);
            else if(!transition.equals("cancel")&&!transition.equals("timeout"))assertEquals(1,await(commit));
            broker.awaitIdle(WAIT);assertEquals(0,broker.consumerDiagnostics().progressCalls());
            if(transition.equals("failure")) {assertEquals(DurableBroker.State.DEGRADED,broker.diagnostics().state());assertEquals(1,broker.consumerDiagnostics().barriers());}
            else if(transition.equals("leave")||transition.equals("expiry"))assertEquals(List.of(1L),await(broker.join(JOIN)).committedOffsets());
            else if(transition.equals("cancel"))assertEquals(1,await(broker.commit(commit(member,1))));
        } finally {disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));}
        try(var reopened=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {
            assertEquals(List.of(transition.equals("failure")?0L:1L),await(reopened.join(JOIN)).committedOffsets());
        }
    }
    @Test void survivingHeartbeatDoesNotPublishReassignmentDuringPinnedExpiry() throws Exception {
        Events disk=new Events();AtomicLong time=new AtomicLong();
        try(var broker=ConsumerTestSupport.start(directory,time,disk)) {
            await(broker.createTopic(TOPIC,1));await(broker.append(command()));var a=await(broker.join(JOIN));var b=await(broker.join(JOIN));a=await(broker.heartbeat(a.identity()));
            disk.pause="commit-forced";var commit=broker.commit(commit(a,1));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            time.set(50_000_000);assertEquals(2,await(broker.heartbeat(b.identity())).generation());time.set(100_000_000);
            var held=await(broker.heartbeat(b.identity()));assertEquals(2,held.generation());assertTrue(held.assignments().isEmpty());
            failure(FENCED,broker.heartbeat(a.identity()));failure(UNAVAILABLE,broker.join(JOIN));
            disk.proceed.countDown();assertEquals(1,await(commit));var reassigned=await(broker.heartbeat(b.identity()));
            assertEquals(3,reassigned.generation());assertEquals(List.of(PART),reassigned.assignments());assertEquals(List.of(1L),reassigned.committedOffsets());
        } finally {disk.proceed.countDown();}
    }
    @Test void inactiveResetMovesBackwardAndFencesConcurrentJoinUntilPersistence() throws Exception {
        Events disk=new Events();
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command()));var member=await(broker.join(JOIN));
            await(broker.commit(commit(member,3)));failure(FENCED,broker.reset(GROUP,PART,0));await(broker.leave(member.identity()));
            failure(OUT_OF_RANGE,broker.reset(GROUP,PART,4));disk.pause="commit-forced";var reset=broker.reset(GROUP,PART,1);
            assertTrue(disk.entered.await(5,TimeUnit.SECONDS));failure(UNAVAILABLE,broker.join(JOIN));disk.proceed.countDown();assertEquals(1,await(reset));
            assertEquals(List.of(1L),await(broker.join(JOIN)).committedOffsets());
        } finally {disk.proceed.countDown();}
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {assertEquals(List.of(1L),await(broker.join(JOIN)).committedOffsets());}
    }
    @Test void joinWinningBeforeQueuedResetApplicationRejectsReset() throws Exception {
        Events disk=new Events();CountDownLatch queued=new CountDownLatch(1);
        try(var broker=DurableBroker.start(directory,OPTIONS,GroupMembershipTest.OPTIONS,CLOCK,new AtomicLong()::get,disk,e->{if(e.equals("progress-queued"))queued.countDown();})) {
            broker.awaitReady(WAIT);await(broker.createTopic(TOPIC,1));await(broker.append(command()));var member=await(broker.join(JOIN));await(broker.leave(member.identity()));
            disk.pause="data-forced";var append=broker.append(command());assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            var reset=broker.reset(GROUP,PART,1);assertTrue(queued.await(5,TimeUnit.SECONDS));var joined=await(broker.join(JOIN));
            disk.proceed.countDown();await(append);failure(FENCED,reset);assertEquals(List.of(0L),joined.committedOffsets());
        } finally {disk.proceed.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"success","failure"})
    void uncertainResetPreventsActivationAndPublishesOnlyActualLateOutcome(String outcome) throws Exception {
        Events disk=new Events();AtomicLong time=new AtomicLong();
        try(var broker=ConsumerTestSupport.start(directory,time,disk)) {
            await(broker.createTopic(TOPIC,1));await(broker.append(command()));var member=await(broker.join(JOIN));await(broker.commit(commit(member,1)));await(broker.leave(member.identity()));
            disk.pause="commit-forced";if(outcome.equals("failure"))disk.fail="commit-forced";var reset=broker.reset(GROUP,PART,0);
            assertTrue(disk.entered.await(5,TimeUnit.SECONDS));time.set(Duration.ofSeconds(31).toNanos());broker.pulse();failure(UNAVAILABLE,reset);
            failure(UNAVAILABLE,broker.join(JOIN));assertEquals(1,broker.consumerDiagnostics().progressCalls());
            disk.proceed.countDown();broker.awaitIdle(WAIT);failure(UNAVAILABLE,broker.join(JOIN));
            assertEquals(outcome.equals("success")?0:1,broker.consumerDiagnostics().barriers());
        } finally {disk.proceed.countDown();}
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {assertEquals(List.of(outcome.equals("success")?0L:1L),await(broker.join(JOIN)).committedOffsets());}
    }
    @Test void qualifiedPartitionsPersistIndependentPositionsAndAssignmentOrder() throws Exception {
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {
            await(broker.createTopic(TOPIC,3));for(int i=0;i<6;i++)await(broker.append(command()));var member=await(broker.join(JOIN));
            await(broker.commit(new CommitCommand(member.identity(),member.generation(),new kaflow.broker.TopicPartition(TOPIC,2),2)));
            await(broker.commit(new CommitCommand(member.identity(),member.generation(),new kaflow.broker.TopicPartition(TOPIC,0),1)));
            assertEquals(List.of(1L,0L,2L),await(broker.heartbeat(member.identity())).committedOffsets());
            var second=await(broker.join(JOIN));assertEquals(List.of(0L),second.committedOffsets());
            assertEquals(List.of(1L,2L),await(broker.heartbeat(member.identity())).committedOffsets());
            failure(FENCED,broker.commit(new CommitCommand(second.identity(),second.generation(),PART,0)));
        }
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),new Persistence())) {assertEquals(List.of(1L,0L,2L),await(broker.join(JOIN)).committedOffsets());}
    }
    @ParameterizedTest @ValueSource(strings={"count","bytes"})
    void progressAdmissionHoldsAcrossCancellationUntilUnderlyingWriterEnds(String limit) throws Exception {
        Events disk=new Events();var options=new ConsumerOptions(1,2,64L*1024*1024,limit.equals("count")?1:8,limit.equals("bytes")?2048:16384);
        try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),disk,options)) {
            await(broker.createTopic(TOPIC,1));await(broker.append(command()));var member=await(broker.join(JOIN));disk.pause="commit-forced";
            var first=broker.commit(commit(member,1));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));first.cancel(false);
            failure(RESOURCE_EXHAUSTED,broker.commit(commit(member,1)));assertEquals(2048,broker.consumerDiagnostics().progressBytes());
            disk.proceed.countDown();broker.awaitIdle(WAIT);assertEquals(0,broker.consumerDiagnostics().progressBytes());assertEquals(1,await(broker.commit(commit(member,1))));
        } finally {disk.proceed.countDown();}
    }
}
