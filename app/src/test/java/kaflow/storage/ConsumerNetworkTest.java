package kaflow.storage;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kaflow.v1.*;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import tributary.TributaryApplication;
import tributary.stream.DurableAppendService;
import static kaflow.storage.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ConsumerNetworkTest {
    @TempDir Path directory;
    static final TopicPartition PARTITION=TopicPartition.newBuilder().setTopic("orders").setPartition(0).build();
    static final JoinRequest JOIN=JoinRequest.newBuilder().setGroup("consumer-a").setTopic("orders").build();
    static FetchRequest fetch(MembershipResponse member,long offset) {return FetchRequest.newBuilder().setSession(member.getSession()).setGeneration(member.getGeneration()).setPartition(PARTITION).setStartOffset(offset).setMaxRecords(128).setMaxBytes(128).build();}
    static CommitRequest commit(MembershipResponse member,long offset) {return CommitRequest.newBuilder().setSession(member.getSession()).setGeneration(member.getGeneration()).setPartition(PARTITION).setNextOffset(offset).build();}
    @Test void springPublicBinaryLifecycleTwoGroupsAndRestartResume() throws Exception {
        AtomicInteger port=new AtomicInteger();MembershipResponse old;
        try(var context=spring(port)) {
            await(context.getBean(DurableBroker.class).createTopic(TOPIC,1));var channel=channel(port.get());
            try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
                var receipt=stub.append(AppendRequest.newBuilder().setTopic("orders").setKey(ByteString.EMPTY).setPayload(ByteString.copyFrom(new byte[]{0,-1,-128,-61,40})).build());
                stub.append(AppendRequest.newBuilder().setTopic("orders").build());old=stub.join(JOIN);var other=stub.join(JOIN.toBuilder().setGroup("consumer-b").build());
                assertEquals(List.of(0L),old.getCommittedNextOffsetsList());var batch=stub.fetch(fetch(old,0));assertEquals(2,batch.getRecordsCount());assertEquals(2,batch.getNextOffset());assertEquals(2,batch.getPublishedEnd());
                var record=batch.getRecords(0);assertEquals(receipt.getTimestampMillis(),record.getTimestampMillis());assertEquals(receipt.getOffset(),record.getOffset());assertEquals(PARTITION,record.getPartition());
                assertArrayEquals(new byte[]{0,-1,-128,-61,40},record.getPayload().toByteArray());assertTrue(record.hasKey());assertTrue(record.getKey().isEmpty());assertFalse(batch.getRecords(1).hasKey());
                assertEquals(List.of(0L),stub.heartbeat(HeartbeatRequest.newBuilder().setSession(old.getSession()).build()).getCommittedNextOffsetsList());
                assertEquals(2,stub.commit(commit(old,2)).getDurableNextOffset());assertEquals(1,stub.commit(commit(other,1)).getDurableNextOffset());
                assertEquals(0,stub.fetch(fetch(old,0)).getRecords(0).getOffset());assertEquals(2,stub.commit(commit(old,0)).getDurableNextOffset());
            } finally {close(channel);}
        }
        try(var context=spring(port)) {
            var channel=channel(port.get());try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
                status(Status.Code.FAILED_PRECONDITION,()->stub.fetch(fetch(old,0)));status(Status.Code.FAILED_PRECONDITION,()->stub.commit(commit(old,2)));
                var a=stub.join(JOIN);var b=stub.join(JOIN.toBuilder().setGroup("consumer-b").build());
                assertEquals(List.of(2L),a.getCommittedNextOffsetsList());assertEquals(List.of(1L),b.getCommittedNextOffsetsList());
                assertEquals(0,stub.fetch(fetch(a,2)).getRecordsCount());assertEquals(1,stub.fetch(fetch(b,1)).getRecords(0).getOffset());
            } finally {close(channel);}
        }
    }
    @Test void networkBoundsAndStorageErrorsAreSanitized() throws Exception {
        Events disk=new Events();try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));await(broker.append(command((byte)1)));Server server=server(broker);var channel=channel(server.getPort());
            try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);var member=stub.join(JOIN);
                status(Status.Code.INVALID_ARGUMENT,()->stub.fetch(FetchRequest.getDefaultInstance()));status(Status.Code.INVALID_ARGUMENT,()->stub.commit(CommitRequest.getDefaultInstance()));
                status(Status.Code.INVALID_ARGUMENT,()->stub.fetch(fetch(member,0).toBuilder().setStartOffset(-1).build()));
                status(Status.Code.INVALID_ARGUMENT,()->stub.commit(commit(member,-1)));
                status(Status.Code.OUT_OF_RANGE,()->stub.fetch(fetch(member,2)));status(Status.Code.OUT_OF_RANGE,()->stub.commit(commit(member,2)));
                status(Status.Code.RESOURCE_EXHAUSTED,()->stub.fetch(fetch(member,0).toBuilder().setMaxBytes(0).build()));
                disk.fail="commit-forced";var error=status(Status.Code.INTERNAL,()->stub.commit(commit(member,1)));
                assertFalse(error.getMessage().contains(member.getSession().getSessionToken()));assertFalse(error.getMessage().contains("/private"));assertFalse(error.getMessage().contains("disk full"));
                status(Status.Code.UNAVAILABLE,()->stub.fetch(fetch(member,0)));
            } finally {close(channel);server.shutdownNow();assertTrue(server.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test void cancelledNetworkCommitHasUnknownOutcomeAndCurrentOwnerRetryIsSafe() throws Exception {
        Events disk=new Events();try(var broker=ConsumerTestSupport.start(directory,new AtomicLong(),disk)) {
            await(broker.createTopic(TOPIC,1));await(broker.append(command()));Server server=server(broker);var channel=channel(server.getPort());
            try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);var member=stub.join(JOIN);disk.pause="commit-synced";
                var result=BrokerGrpc.newFutureStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS).commit(commit(member,1));assertTrue(disk.entered.await(5,TimeUnit.SECONDS));assertFalse(result.isDone());result.cancel(false);
                assertEquals(1,stub.heartbeat(HeartbeatRequest.newBuilder().setSession(member.getSession()).build()).getGeneration());status(Status.Code.UNAVAILABLE,()->stub.join(JOIN));
                disk.proceed.countDown();broker.awaitIdle(WAIT);assertEquals(1,stub.commit(commit(member,1)).getDurableNextOffset());assertTrue(result.isCancelled());
            } finally {disk.proceed.countDown();close(channel);server.shutdownNow();assertTrue(server.awaitTermination(5,TimeUnit.SECONDS));}
        } finally {disk.proceed.countDown();}
    }
    private ConfigurableApplicationContext spring(AtomicInteger port) {
        var app=new SpringApplication(TributaryApplication.class);app.setRegisterShutdownHook(false);app.addListeners((ApplicationListener<GrpcServerStartedEvent>)e->port.set(e.getPort()));
        return app.run("--server.port=0","--server.address=127.0.0.1","--grpc.server.port=0","--grpc.server.address=127.0.0.1","--grpc.server.shutdown-grace-period=5s","--spring.main.banner-mode=off","--kaflow.durable.enabled=true","--kaflow.durable.directory="+directory);
    }
    private Server server(DurableBroker broker)throws Exception{return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(new DurableAppendService(broker,OPTIONS)).build().start();}
    private static ManagedChannel channel(int port){return ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build();}
    private static void close(ManagedChannel channel)throws Exception{assertTrue(channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS));}
    private static StatusRuntimeException status(Status.Code code,org.junit.jupiter.api.function.Executable call){var error=assertThrows(StatusRuntimeException.class,call);assertEquals(code,error.getStatus().getCode());return error;}
}
