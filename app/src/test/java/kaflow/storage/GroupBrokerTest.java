package kaflow.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import kaflow.groups.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableTestSupport.*;
import static kaflow.storage.GroupMembershipTest.GROUP;
import static kaflow.storage.GroupMembershipTest.JOIN;
import static kaflow.storage.GroupMembershipTest.partitions;
import static kaflow.storage.DurableException.Code.*;
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GroupBrokerTest {
    @TempDir Path directory;
    private DurableBroker start(GroupOptions groups, AtomicLong time, Persistence disk) throws Exception {
        var broker=DurableBroker.start(directory,OPTIONS,groups,CLOCK,time::get,disk,e->{});broker.awaitReady(WAIT);return broker;
    }
    private GroupOptions limits(int joins,int calls,long bytes) {return GroupMembershipTest.options(8,8,16,joins,calls,bytes,131072);}

    @Test void definitionsSurviveButSessionsAndIncarnationDoNotAndAppendStillWorks() throws Exception {
        MembershipView old;
        try(var broker=start(GroupMembershipTest.OPTIONS,new AtomicLong(),new Persistence())) {
            await(broker.createTopic(TOPIC,3));old=await(broker.join(JOIN));assertEquals(1,old.generation());
            assertEquals(List.of(0,1,2),partitions(old));assertEquals(0,await(broker.append(command())).offset());
        }
        try(var broker=start(GroupMembershipTest.OPTIONS,new AtomicLong(),new Persistence())) {
            assertEquals(1,broker.membershipDiagnostics().groups());assertEquals(0,broker.membershipDiagnostics().members());
            failure(FENCED,broker.heartbeat(old.identity()));failure(FENCED,broker.leave(old.identity()));
            var current=await(broker.join(JOIN));assertNotEquals(old.identity().incarnation(),current.identity().incarnation());
            assertEquals(1,current.generation());await(broker.validateOwnership(current.identity(),1,new TopicPartition(TOPIC,0)));
            failure(FENCED,broker.validateOwnership(old.identity(),1,new TopicPartition(TOPIC,0)));
            assertEquals(1,await(broker.append(command())).partition().partition());
        }
    }

    @Test void concurrentSameBindingCreatesOneDefinitionConflictingCreationRejects() throws Exception {
        Events disk=new Events();
        try(var broker=start(GroupMembershipTest.OPTIONS,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,3));var other=new TopicId("other");await(broker.createTopic(other,1));disk.pause="group-catalog-synced";
            var first=broker.join(JOIN);assertTrue(disk.entered.await(5,TimeUnit.SECONDS));assertFalse(first.isDone());
            var second=broker.join(JOIN);var conflict=broker.join(new JoinCommand(GROUP,other,"worker"));
            disk.proceed.countDown();var a=await(first);var b=await(second);failure(BINDING_CONFLICT,conflict);
            assertNotEquals(a.identity().session(),b.identity().session());assertEquals(2,await(broker.heartbeat(a.identity())).generation());
            assertEquals(1,broker.membershipDiagnostics().groups());assertEquals(2,broker.membershipDiagnostics().members());
            assertEquals(1,disk.events.stream().filter("group-catalog-synced"::equals).count());
        } finally {disk.proceed.countDown();}
    }

    @Test void cancellationDuringCreationKeepsReservationAndLostSessionConsumesCapacity() throws Exception {
        Events disk=new Events();var opts=GroupMembershipTest.options(8,1,1,1,4,65536,131072);AtomicLong time=new AtomicLong();
        try(var broker=start(opts,time,disk)) {
            await(broker.createTopic(TOPIC,1));disk.pause="group-catalog-synced";
            var join=broker.join(JOIN);assertTrue(disk.entered.await(5,TimeUnit.SECONDS));assertTrue(join.cancel(false));
            assertEquals(1,broker.membershipDiagnostics().pendingJoins());assertEquals(1,broker.membershipDiagnostics().admittedControls());
            failure(RESOURCE_EXHAUSTED,broker.join(JOIN));assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));
            disk.proceed.countDown();broker.awaitIdle(WAIT);assertTrue(join.isCancelled());
            assertEquals(1,broker.membershipDiagnostics().members());failure(RESOURCE_EXHAUSTED,broker.join(JOIN));
            time.set(100_000_000);var next=await(broker.join(JOIN));assertEquals(3,next.generation());
            assertEquals(1,broker.membershipDiagnostics().members());
        } finally {disk.proceed.countDown();}
    }

    @ParameterizedTest @ValueSource(strings={"joins","controls","bytes"})
    void independentControlAdmissionLimitsRetainAndReleaseReservations(String resource) throws Exception {
        // 7 group + 6 topic + 6 label + (512 + 8*(255+16)) response bytes = 2699.
        var limits=limits(resource.equals("joins")?1:8,resource.equals("controls")?1:8,resource.equals("bytes")?2699:65536);
        Events disk=new Events();
        try(var broker=start(limits,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));disk.pause="group-catalog-synced";
            var first=broker.join(JOIN);assertTrue(disk.entered.await(5,TimeUnit.SECONDS));assertEquals(2699,broker.membershipDiagnostics().admittedBytes());
            failure(RESOURCE_EXHAUSTED,broker.join(JOIN));
            disk.proceed.countDown();await(first);broker.awaitIdle(WAIT);
            assertEquals(0,broker.membershipDiagnostics().pendingJoins());assertEquals(0,broker.membershipDiagnostics().admittedBytes());
            assertEquals(2,await(broker.join(JOIN)).generation());
        } finally {disk.proceed.countDown();}
    }

    @Test void heartbeatAndExpiryBypassBlockedWriterAndAppendAdmission() throws Exception {
        AtomicLong time=new AtomicLong();Events disk=new Events();
        try(var broker=start(GroupMembershipTest.OPTIONS,time,disk)) {
            await(broker.createTopic(TOPIC,3));var a=await(broker.join(JOIN));var b=await(broker.join(JOIN));broker.awaitIdle(WAIT);
            disk.pause="data-forced";var append=broker.append(command());assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            for(int i=1;i<OPTIONS.maxCalls();i++)broker.append(command());failure(RESOURCE_EXHAUSTED,broker.append(command()));
            time.set(50_000_000);assertEquals(2,await(broker.heartbeat(b.identity())).generation());
            time.set(100_000_000);var survivor=await(broker.heartbeat(b.identity()));assertEquals(3,survivor.generation());
            assertEquals(List.of(0,1,2),partitions(survivor));failure(FENCED,broker.heartbeat(a.identity()));
            await(broker.validateOwnership(b.identity(),3,new TopicPartition(TOPIC,0)));assertFalse(append.isDone());
            assertEquals(1,broker.membershipDiagnostics().timerEntries());
            disk.proceed.countDown();await(append);broker.awaitIdle(WAIT);
        } finally {disk.proceed.countDown();}
    }

    @Test void degradedHeartbeatRenewsWithoutRestoringReadinessAndShutdownRetainsWriter() throws Exception {
        var groups=new GroupOptions(8,8,16,8,16,65536,131072,Duration.ofMinutes(1));AtomicLong time=new AtomicLong();Events disk=new Events();
        try(var broker=start(groups,time,disk)) {
            await(broker.createTopic(TOPIC,1));var member=await(broker.join(JOIN));broker.awaitIdle(WAIT);
            disk.pause="data-forced";var append=broker.append(command());assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            time.set(Duration.ofSeconds(31).toNanos());broker.pulse();failure(UNAVAILABLE,append);
            assertEquals(DurableBroker.State.DEGRADED,broker.diagnostics().state());
            assertEquals(1,await(broker.heartbeat(member.identity())).generation());assertFalse(broker.diagnostics().ready());
            failure(UNAVAILABLE,broker.join(JOIN));failure(UNAVAILABLE,broker.validateOwnership(member.identity(),1,PART));
            assertFalse(broker.shutdown(Duration.ofMillis(20)));failure(UNAVAILABLE,broker.heartbeat(member.identity()));
            assertThrows(IOException.class,()->DurableStore.open(directory,OPTIONS,CLOCK,new Persistence()));
            disk.proceed.countDown();assertTrue(broker.shutdown(WAIT));assertEquals(0,broker.membershipDiagnostics().members());
            assertEquals(0,broker.membershipDiagnostics().admittedControls());assertEquals(1,broker.diagnostics().published().get(PART));
        } finally {disk.proceed.countDown();}
    }

    @Test void lateGroupCreationAfterCloseRecoversDefinitionWithoutAllocatingSession() throws Exception {
        Events disk=new Events();
        try(var broker=start(GroupMembershipTest.OPTIONS,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));disk.pause="group-catalog-synced";var join=broker.join(JOIN);
            assertTrue(disk.entered.await(5,TimeUnit.SECONDS));assertFalse(broker.shutdown(Duration.ofMillis(20)));
            disk.proceed.countDown();failure(UNAVAILABLE,join);assertTrue(broker.shutdown(WAIT));
            assertEquals(0,broker.membershipDiagnostics().members());assertEquals(0,broker.membershipDiagnostics().pendingJoins());
        } finally {disk.proceed.countDown();}
        try(var broker=start(GroupMembershipTest.OPTIONS,new AtomicLong(),new Persistence())) {
            assertEquals(1,broker.membershipDiagnostics().groups());assertEquals(1,await(broker.join(JOIN)).generation());
        }
    }

    @Test void blockedControlCallbacksKeepAccountingBoundedButCannotBlockCoordinator() throws Exception {
        Events disk=new Events();CountDownLatch callbacks=new CountDownLatch(2),release=new CountDownLatch(1);
        try(var broker=start(limits(8,3,65536),new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));disk.pause="group-catalog-synced";
            var a=broker.join(JOIN);assertTrue(disk.entered.await(5,TimeUnit.SECONDS));var b=broker.join(JOIN);
            for(var future:List.of(a,b))future.thenRun(()->{callbacks.countDown();try{barrier(release);}catch(IOException e){throw new RuntimeException(e);}});
            disk.proceed.countDown();assertTrue(callbacks.await(5,TimeUnit.SECONDS));
            var current=await(a);var heartbeat=broker.heartbeat(current.identity());
            failure(RESOURCE_EXHAUSTED,broker.heartbeat(current.identity()));
            assertEquals(3,broker.membershipDiagnostics().admittedControls());assertEquals(2,broker.membershipDiagnostics().members());
            assertEquals(0,await(broker.append(command())).offset()); // Separate response workers.
            release.countDown();assertEquals(2,await(heartbeat).generation());broker.awaitIdle(WAIT);
            assertEquals(0,broker.membershipDiagnostics().admittedControls());
        } finally {disk.proceed.countDown();release.countDown();}
    }

    @ParameterizedTest @ValueSource(strings={"success","failure"})
    void watchdogCreationCompletionNeverAllocatesALateSession(String outcome) throws Exception {
        Events disk=new Events();AtomicLong time=new AtomicLong();
        try(var broker=start(GroupMembershipTest.OPTIONS,time,disk)) {
            await(broker.createTopic(TOPIC,1));disk.pause="group-catalog-forced";
            if(outcome.equals("failure"))disk.fail="group-catalog-forced";
            var join=broker.join(JOIN);assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            time.set(Duration.ofSeconds(31).toNanos());broker.pulse();failure(UNAVAILABLE,join);
            assertEquals(1,broker.membershipDiagnostics().pendingJoins());assertFalse(broker.diagnostics().ready());
            disk.proceed.countDown();broker.awaitIdle(WAIT);
            assertEquals(0,broker.membershipDiagnostics().members());assertEquals(0,broker.membershipDiagnostics().pendingJoins());
            assertEquals(outcome.equals("success")?1:0,broker.membershipDiagnostics().groups());
            assertEquals(DurableBroker.State.DEGRADED,broker.diagnostics().state());failure(UNAVAILABLE,broker.join(JOIN));
        } finally {disk.proceed.countDown();}
        try(var broker=start(GroupMembershipTest.OPTIONS,new AtomicLong(),new Persistence())) {
            assertEquals(outcome.equals("success")?1:0,broker.membershipDiagnostics().groups());assertEquals(1,await(broker.join(JOIN)).generation());
        }
    }

    @Test void closeDrainsMoreThanOneControlBatchAndInvalidatesAllSessions() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),proceed=new CountDownLatch(1);
        var pause=new java.util.concurrent.atomic.AtomicBoolean();
        try(var broker=DurableBroker.start(directory,OPTIONS,limits(8,32,131072),CLOCK,new AtomicLong()::get,new Persistence(),event->{
            if(pause.get() && event.equals("published")){entered.countDown();try{barrier(proceed);}catch(IOException e){throw new RuntimeException(e);}}
        })) {
            broker.awaitReady(WAIT);await(broker.createTopic(TOPIC,1));var member=await(broker.join(JOIN));broker.awaitIdle(WAIT);
            pause.set(true);var append=broker.append(command());assertTrue(entered.await(5,TimeUnit.SECONDS));
            var controls=new java.util.ArrayList<CompletableFuture<MembershipView>>();
            for(int i=0;i<20;i++)controls.add(broker.heartbeat(member.identity()));
            broker.requestClose();proceed.countDown();await(append);
            for(var control:controls)failure(UNAVAILABLE,control);
            assertTrue(broker.shutdown(WAIT));assertEquals(0,broker.membershipDiagnostics().admittedControls());
            assertEquals(0,broker.membershipDiagnostics().members());assertEquals(0,broker.membershipDiagnostics().queuedControls());
            failure(UNAVAILABLE,broker.leave(member.identity()));
        } finally {proceed.countDown();}
    }

    @Test void wallClockJumpsDoNotControlLeasesAndRenewalsDoNotGrowTimers() throws Exception {
        AtomicLong wall=new AtomicLong(),time=new AtomicLong();Clock clock=new Clock(){
            public ZoneId getZone(){return ZoneId.of("UTC");}public Clock withZone(ZoneId z){return this;}
            public Instant instant(){return Instant.ofEpochMilli(wall.get());}
        };
        try(var broker=DurableBroker.start(directory,OPTIONS,GroupMembershipTest.OPTIONS,clock,time::get,new Persistence(),e->{})) {
            broker.awaitReady(WAIT);await(broker.createTopic(TOPIC,1));var a=await(broker.join(JOIN));
            for(int i=0;i<200;i++) {wall.set(i%2==0?Long.MAX_VALUE:Long.MIN_VALUE);assertEquals(1,await(broker.heartbeat(a.identity())).generation());}
            assertEquals(1,broker.membershipDiagnostics().timerEntries());time.set(100_000_000);
            failure(FENCED,broker.heartbeat(a.identity()));broker.awaitIdle(WAIT);assertEquals(0,broker.membershipDiagnostics().timerEntries());
        }
    }
}
