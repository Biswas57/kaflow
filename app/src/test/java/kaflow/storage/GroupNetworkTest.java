package kaflow.storage;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import static kaflow.storage.StorageTestSupport.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class GroupNetworkTest {
    @TempDir Path directory;
    private static final JoinRequest JOIN=JoinRequest.newBuilder().setGroup("billing").setTopic("orders").setMemberLabel("same-label").build();
    @Test void optInSpringJoinHeartbeatAssignmentLeaveAndRestartFencing() throws Exception {
        AtomicInteger port=new AtomicInteger();SessionIdentity old;
        try(var context=spring(port)) {
            await(context.getBean(DurableBroker.class).createTopic(TOPIC,3));var channel=channel(port.get());
            try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
                var a=stub.join(JOIN);old=a.getSession();assertEquals(List.of(0,1,2),parts(a));assertEquals(30000,a.getLeaseDurationMillis());
                var b=stub.join(JOIN);assertEquals(List.of(1),parts(b));assertNotEquals(old,b.getSession());
                var current=stub.heartbeat(HeartbeatRequest.newBuilder().setSession(old).build());assertEquals(2,current.getGeneration());assertEquals(List.of(0,2),parts(current));
                stub.leave(LeaveRequest.newBuilder().setSession(b.getSession()).build());
                assertEquals(List.of(0,1,2),parts(stub.heartbeat(HeartbeatRequest.newBuilder().setSession(old).build())));
                assertStatus(Status.Code.FAILED_PRECONDITION,()->stub.leave(LeaveRequest.newBuilder().setSession(b.getSession()).build()));
                var independent=stub.join(JOIN.toBuilder().setGroup("shipping").build());assertEquals(List.of(0,1,2),parts(independent));
                assertEquals(0,stub.append(AppendRequest.newBuilder().setTopic("orders").build()).getOffset());
            } finally {close(channel);}
        }
        try(var context=spring(port)) {
            assertEquals(2,context.getBean(DurableBroker.class).membershipDiagnostics().groups());var channel=channel(port.get());
            try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
                assertStatus(Status.Code.FAILED_PRECONDITION,()->stub.heartbeat(HeartbeatRequest.newBuilder().setSession(old).build()));
                assertStatus(Status.Code.FAILED_PRECONDITION,()->stub.leave(LeaveRequest.newBuilder().setSession(old).build()));
                var fresh=stub.join(JOIN);assertNotEquals(old.getIncarnation(),fresh.getSession().getIncarnation());assertEquals(1,fresh.getGeneration());
                assertEquals(List.of(0,1,2),parts(fresh));
            } finally {close(channel);}
        }
    }

    @Test void networkErrorsAreSanitizedAndExpiryCannotReviveTokens() throws Exception {
        AtomicLong time=new AtomicLong();Events disk=new Events();
        try(var broker=DurableBroker.start(directory,OPTIONS,GroupMembershipTest.OPTIONS,CLOCK,time::get,disk,e->{})) {
            broker.awaitReady(WAIT);await(broker.createTopic(TOPIC,1));await(broker.createTopic(new kaflow.broker.TopicId("other"),1));
            Server server=server(broker);var channel=channel(server.getPort());
            try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
                assertStatus(Status.Code.INVALID_ARGUMENT,()->stub.join(JoinRequest.getDefaultInstance()));
                assertStatus(Status.Code.INVALID_ARGUMENT,()->stub.heartbeat(HeartbeatRequest.getDefaultInstance()));
                assertStatus(Status.Code.INVALID_ARGUMENT,()->stub.leave(LeaveRequest.getDefaultInstance()));
                assertStatus(Status.Code.NOT_FOUND,()->stub.join(JOIN.toBuilder().setTopic("missing").build()));
                var member=stub.join(JOIN);assertStatus(Status.Code.FAILED_PRECONDITION,()->stub.join(JOIN.toBuilder().setTopic("other").build()));
                var unknown=member.getSession().toBuilder().setGroup("unknown").build();
                assertStatus(Status.Code.NOT_FOUND,()->stub.heartbeat(HeartbeatRequest.newBuilder().setSession(unknown).build()));
                time.set(100_000_000);var error=assertStatus(Status.Code.FAILED_PRECONDITION,()->stub.heartbeat(HeartbeatRequest.newBuilder().setSession(member.getSession()).build()));
                assertFalse(error.getMessage().contains(member.getSession().getSessionToken()));
                disk.fail="group-catalog-forced";var io=assertStatus(Status.Code.INTERNAL,()->stub.join(JOIN.toBuilder().setGroup("new").build()));
                assertFalse(io.getMessage().contains("/private"));assertFalse(io.getMessage().contains("disk full"));
                assertStatus(Status.Code.UNAVAILABLE,()->stub.join(JOIN));
            } finally {close(channel);server.shutdownNow();assertTrue(server.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }

    @Test void cancelledCreationHasNoEarlyResponseAndRetryMayLeaveTwoLiveSessions() throws Exception {
        Events disk=new Events();
        try(var broker=DurableBroker.start(directory,OPTIONS,kaflow.groups.GroupOptions.defaults(),CLOCK,System::nanoTime,disk,e->{})) {
            broker.awaitReady(WAIT);await(broker.createTopic(TOPIC,1));disk.pause="group-catalog-synced";
            Server server=server(broker);var channel=channel(server.getPort());
            try {
                var join=BrokerGrpc.newFutureStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS).join(JOIN);
                assertTrue(disk.entered.await(5,TimeUnit.SECONDS));assertFalse(join.isDone());join.cancel(false);
                assertEquals(1,broker.membershipDiagnostics().pendingJoins());disk.proceed.countDown();broker.awaitIdle(WAIT);
                var retry=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS).join(JOIN);
                assertEquals(2,retry.getGeneration());assertTrue(retry.getAssignmentsList().isEmpty());assertEquals(2,broker.membershipDiagnostics().members());
            } finally {disk.proceed.countDown();close(channel);server.shutdownNow();assertTrue(server.awaitTermination(5,TimeUnit.SECONDS));}
        } finally {disk.proceed.countDown();}
    }

    @Test void realHeartbeatBypassesStorageAndReportsCapacityWithoutLeakingSession() throws Exception {
        Events disk=new Events();var groups=GroupMembershipTest.options(8,1,1,4,8,65536,131072);
        try(var broker=DurableBroker.start(directory,OPTIONS,groups,CLOCK,new AtomicLong()::get,disk,e->{})) {
            broker.awaitReady(WAIT);await(broker.createTopic(TOPIC,1));Server server=server(broker);var channel=channel(server.getPort());
            try {
                var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);var member=stub.join(JOIN);
                assertStatus(Status.Code.RESOURCE_EXHAUSTED,()->stub.join(JOIN));
                disk.pause="data-forced";var append=BrokerGrpc.newFutureStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS)
                        .append(AppendRequest.newBuilder().setTopic("orders").build());assertTrue(disk.entered.await(5,TimeUnit.SECONDS));
                assertEquals(1,stub.heartbeat(HeartbeatRequest.newBuilder().setSession(member.getSession()).build()).getGeneration());
                assertFalse(append.isDone());disk.proceed.countDown();assertEquals(0,append.get(5,TimeUnit.SECONDS).getOffset());
            } finally {disk.proceed.countDown();close(channel);server.shutdownNow();assertTrue(server.awaitTermination(5,TimeUnit.SECONDS));}
        } finally {disk.proceed.countDown();}
    }

    private ConfigurableApplicationContext spring(AtomicInteger port) {
        var app=new SpringApplication(TributaryApplication.class);app.setRegisterShutdownHook(false);
        app.addListeners((ApplicationListener<GrpcServerStartedEvent>)e->port.set(e.getPort()));
        return app.run("--server.port=0","--server.address=127.0.0.1","--grpc.server.port=0","--grpc.server.address=127.0.0.1",
                "--grpc.server.shutdown-grace-period=5s","--spring.main.banner-mode=off","--kaflow.durable.enabled=true","--kaflow.durable.directory="+directory);
    }
    private Server server(DurableBroker broker)throws Exception{return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(new DurableAppendService(broker,OPTIONS)).build().start();}
    private static List<Integer> parts(MembershipResponse response){return response.getAssignmentsList().stream().map(TopicPartition::getPartition).toList();}
    private static ManagedChannel channel(int port){return ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build();}
    private static void close(ManagedChannel channel)throws Exception{assertTrue(channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS));}
    private static StatusRuntimeException assertStatus(Status.Code code,org.junit.jupiter.api.function.Executable call){var e=assertThrows(StatusRuntimeException.class,call);assertEquals(code,e.getStatus().getCode());return e;}
}
