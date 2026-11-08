package kaflow.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
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
class LifecycleShutdownTest {
    @TempDir Path directory;
    DurableBroker start(Events disk) throws Exception {
        var broker=DurableBroker.start(directory,OPTIONS,GroupOptions.defaults(),ConsumerOptions.defaults(),WaitOptions.defaults(),RetentionStoreTest.RETAIN,CLOCK,new AtomicLong()::get,disk,e->{});
        broker.awaitReady(WAIT);return broker;
    }
    @ParameterizedTest @ValueSource(strings={"append","commit","read","cancelled-read","delete","response","transport"})
    void singleDeadlineKeepsLiveOwnerAndLateCompletionFinishesCleanup(String phase) throws Exception {
        Events disk=new Events();CountDownLatch delivery=new CountDownLatch(1),release=new CountDownLatch(1);
        var broker=start(disk);
        try {
            await(broker.createTopic(TOPIC,1));for(int i=0;i<3;i++)await(broker.append(command()));var member=await(broker.join(JOIN));
            CompletableFuture<?> call=null;
            switch(phase) {
                case "append" -> {disk.pause="data-forced";call=broker.append(command());}
                case "commit" -> {disk.pause="commit-forced";call=broker.commit(commit(member,1));}
                case "read","cancelled-read" -> {disk.pause="fetch-read-started";call=broker.fetch(fetch(member,0,1,10));}
                case "delete" -> {disk.pause="retention-delete-before";await(broker.append(command()));}
                case "response" -> call=broker.fetch(fetch(member,0,1,10),(v,e)->{delivery.countDown();try{barrier(release);}catch(Exception x){throw new RuntimeException(x);}});
                case "transport" -> {
                    var executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));broker.ownTransportExecutor(executor);
                    executor.execute(()->{delivery.countDown();try{barrier(release);}catch(Exception e){throw new RuntimeException(e);}});
                }
            }
            if(phase.equals("response")||phase.equals("transport"))assertTrue(delivery.await(5,TimeUnit.SECONDS));else assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
            if(phase.equals("cancelled-read"))assertTrue(call.cancel(false));
            long began=System.nanoTime();assertFalse(broker.shutdown(Duration.ofMillis(25)));long elapsed=System.nanoTime()-began;
            assertTrue(elapsed<TimeUnit.SECONDS.toNanos(1),"one total deadline, not per-worker timeouts");
            assertEquals(DurableBroker.State.CLOSING,broker.diagnostics().state());assertFalse(broker.diagnostics().ready());
            failure(UNAVAILABLE,broker.append(command()));assertEquals("CONFLICT",probe(directory));
            if(phase.contains("read"))assertEquals(1,broker.lifecycleDiagnostics().segmentPins());
            if(phase.equals("commit"))assertEquals(1,broker.consumerDiagnostics().barriers());
            if(phase.equals("delete"))assertTrue(Files.exists(log(directory)));
            assertFalse(broker.shutdown(Duration.ZERO));
            disk.proceed.countDown();release.countDown();assertTrue(broker.shutdown(WAIT));assertTrue(broker.shutdown(Duration.ZERO));
            assertEquals(DurableBroker.State.CLOSED,broker.diagnostics().state());assertEquals(0,broker.diagnostics().admittedCalls());
            assertEquals(0,broker.consumerDiagnostics().reads());assertEquals(0,broker.consumerDiagnostics().progressCalls());
            assertEquals(0,broker.lifecycleDiagnostics().segmentPins());assertEquals(0,broker.lifecycleDiagnostics().reservedBytes());
        } finally {disk.proceed.countDown();release.countDown();assertTrue(broker.shutdown(WAIT));}
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())) {assertEquals(phase.equals("append")||phase.equals("delete")?4:3,store.snapshot(PART).end);}
    }
    @Test void parkedWaitersAndQueuedWorkDrainWithoutReaderPins() throws Exception {
        Events disk=new Events();var broker=start(disk);
        try {
            await(broker.createTopic(TOPIC,1));var member=await(broker.join(JOIN));
            var wait=broker.fetch(new FetchCommand(member.identity(),member.generation(),PART,0,1,1,30000));broker.awaitParked(1,WAIT);
            assertEquals(0,broker.lifecycleDiagnostics().segmentPins());
            assertTrue(broker.shutdown(WAIT));failure(UNAVAILABLE,wait);assertEquals(0,broker.waitDiagnostics().admitted());assertEquals(0,broker.waitDiagnostics().timers());
        } finally {assertTrue(broker.shutdown(WAIT));}
    }
    @Test void listenerTerminationIsPartOfCloseAndContainerCleanupGetsNoSecondBudget() throws Exception {
        var broker=start(new Events());var quiesced=new java.util.concurrent.atomic.AtomicBoolean();var stopped=new java.util.concurrent.atomic.AtomicBoolean();
        broker.ownTransportLifecycle(()->stopped.set(true),quiesced::get);
        try {
            assertFalse(broker.shutdown(Duration.ofMillis(25)));assertTrue(stopped.get());assertEquals("CONFLICT",probe(directory));
            long began=System.nanoTime();assertThrows(java.io.IOException.class,broker::closeFromContainer);
            assertTrue(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(1));assertEquals(DurableBroker.State.CLOSING,broker.diagnostics().state());
            quiesced.set(true);broker.pulse();assertTrue(broker.shutdown(WAIT));broker.closeFromContainer();
        } finally {quiesced.set(true);assertTrue(broker.shutdown(WAIT));}
    }
    @Test void cleanupFailureIsReportedWithItsCauseAfterResourcesActuallyUnwind() throws Exception {
        var io=new StorageTestSupport.ControlledIo();
        var broker=DurableBroker.start(directory,OPTIONS,CLOCK,new AtomicLong()::get,new Persistence(io),e->{});broker.awaitReady(WAIT);
        await(broker.createTopic(TOPIC,1));await(broker.append(command()));io.armed=true;io.failClose=true;
        var error=assertThrows(java.io.IOException.class,()->broker.shutdown(WAIT));assertSame(io.cleanup,error.getCause());
        assertEquals(DurableBroker.State.CLOSED,broker.diagnostics().state());assertEquals(0,io.live);
        assertSame(io.cleanup,assertThrows(java.io.IOException.class,()->broker.shutdown(Duration.ZERO)).getCause());
        try(var store=DurableStore.open(directory,OPTIONS,CLOCK,new Persistence())){assertEquals(1,store.snapshot(PART).end);}
    }
    static String probe(Path directory) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String cp=Path.of(DirectoryLockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator+Path.of(PartitionLog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path output=Files.createTempFile("kf008-lock-", ".log");
        var process=new ProcessBuilder(executable,"-cp",cp,DirectoryLockProbe.class.getName(),directory.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {assertTrue(process.waitFor(5,TimeUnit.SECONDS),"lock probe timeout");String result=Files.readString(output).strip();assertEquals(0,process.exitValue(),result);return result;}
        finally {if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}Files.deleteIfExists(output);}
    }
}
