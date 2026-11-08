package kaflow.storage;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kaflow.broker.TopicId;
import kaflow.v1.*;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import tributary.TributaryApplication;

/** Separate JVM and real Spring/gRPC, using production classes extracted from the executable JAR. */
public final class RetentionSmokeClient {
    public static void main(String[] args) throws Exception {
        int phase=Integer.parseInt(args[2]);AtomicInteger grpc=new AtomicInteger();
        var app=new SpringApplication(TributaryApplication.class);app.setRegisterShutdownHook(false);
        app.addListeners((ApplicationListener<GrpcServerStartedEvent>)e->grpc.set(e.getPort()));
        var context=app.run("--server.port=0","--server.address=127.0.0.1","--grpc.server.port=0","--grpc.server.address=127.0.0.1",
                "--spring.main.banner-mode=off","--kaflow.durable.enabled=true","--kaflow.durable.directory="+args[0],
                "--kaflow.durable.segment-bytes=160","--kaflow.durable.max-segments=16","--kaflow.durable.retention-bytes=200");
        int http=((ServletWebServerApplicationContext)context).getWebServer().getPort();
        var broker=context.getBean(DurableBroker.class);var part=new kaflow.broker.TopicPartition(new TopicId("orders"),0);
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",grpc.get()).usePlaintext().build();
        long closeStarted=0;int parked=16;
        try {
            if(phase==0)broker.createTopic(part.topic(),1).get(5,TimeUnit.SECONDS);
            var sync=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS);
            var a=sync.join(JoinRequest.newBuilder().setGroup("retention-a").setTopic("orders").build());
            var b=sync.join(JoinRequest.newBuilder().setGroup("retention-b").setTopic("orders").build());
            check(a.getCommittedNextOffsetsList().equals(List.of(phase==0?0L:9L)),"group A recovered progress");
            check(b.getCommittedNextOffsetsList().equals(List.of(0L)),"lagging group recovered progress");
            for(int i=phase==0?0:10;i<(phase==0?10:11);i++) {
                var receipt=sync.append(AppendRequest.newBuilder().setTopic("orders").setKey(ByteString.EMPTY).setPayload(payload(i)).build());
                check(receipt.getOffset()==i,"original offset after restart");
            }
            broker.awaitRetention(part,9,0,Duration.ofSeconds(5));long end=phase==0?10:11;
            a=sync.heartbeat(HeartbeatRequest.newBuilder().setSession(a.getSession()).build());
            b=sync.heartbeat(HeartbeatRequest.newBuilder().setSession(b.getSession()).build());
            check(a.getRetainedStartOffsetsList().equals(List.of(9L))&&a.getPublishedEndOffsetsList().equals(List.of(end)),"structured retained range");
            check(b.getCommittedNextOffsetsList().equals(List.of(0L)),"retention must not change progress");
            var wirePart=TopicPartition.newBuilder().setTopic("orders").setPartition(0).build();
            var request=FetchRequest.newBuilder().setSession(a.getSession()).setGeneration(a.getGeneration()).setPartition(wirePart).setMaxRecords(128).setMaxBytes(1024);
            for(long invalid:new long[]{8,end+1})try{sync.fetch(request.setStartOffset(invalid).build());throw new AssertionError("missing OUT_OF_RANGE");}
                catch(StatusRuntimeException e){check(e.getStatus().getCode()==Status.Code.OUT_OF_RANGE,"range error code");}
            var result=sync.fetch(request.setStartOffset(9).build());check(result.getRecordsCount()==end-9&&result.getNextOffset()==end,"retained result boundaries");
            for(int i=0;i<result.getRecordsCount();i++){var r=result.getRecords(i);check(r.getOffset()==9+i&&r.getPartition().equals(wirePart)&&r.hasKey()&&r.getKey().isEmpty()&&r.getPayload().equals(payload(9+i)),"exact retained bytes/identity");}
            check(sync.fetch(request.setStartOffset(end).build()).getRecordsCount()==0,"at end empty");
            if(phase==0)check(sync.commit(CommitRequest.newBuilder().setSession(a.getSession()).setGeneration(a.getGeneration()).setPartition(wirePart).setNextOffset(9).build()).getDurableNextOffset()==9,"durable group progress");
            var calls=new ArrayList<com.google.common.util.concurrent.ListenableFuture<FetchResponse>>();
            var future=BrokerGrpc.newFutureStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS);
            for(int i=0;i<parked;i++)calls.add(future.fetch(request.setStartOffset(end).setMaxWaitMillis(10000).build()));
            broker.awaitParked(parked,Duration.ofSeconds(5));check(broker.consumerDiagnostics().reads()==0&&broker.lifecycleDiagnostics().segmentPins()==0,"parked load holds no readers/pins");
            closeStarted=System.nanoTime();context.close();
            check(broker.diagnostics().state()==DurableBroker.State.CLOSED,"shutdown completed");
            for(var call:calls)try{call.get(5,TimeUnit.SECONDS);throw new AssertionError("close must reject wait");}catch(java.util.concurrent.ExecutionException e){check(Status.fromThrowable(e.getCause()).getCode()==Status.Code.UNAVAILABLE,"wait shutdown result");}
            check(broker.waitDiagnostics().admitted()==0&&broker.consumerDiagnostics().reads()==0&&broker.lifecycleDiagnostics().segmentPins()==0,"shutdown accounting drained");
        } finally {context.close();if(!channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("channel cleanup");}
        double seconds=(System.nanoTime()-closeStarted)/1e9;
        check(!open(http)&&!open(grpc.get()),"listeners closed");
        Files.writeString(Path.of(args[1]),"{\"passed\":true,\"phase\":"+phase+",\"retained_start\":9,\"published_end\":"+(phase==0?10:11)
                +",\"lagging_progress\":0,\"parked_at_shutdown\":"+parked+",\"pins_while_parked\":0,\"listeners_closed\":true,\"shutdown_seconds\":"+seconds+"}\n");
        System.out.println("RETENTION phase="+phase+" start=9 end="+(phase==0?10:11)+" binary=exact progress=independent shutdown=drained");
    }
    private static ByteString payload(int i){return ByteString.copyFrom(new byte[]{(byte)i,0,-1});}
    private static void check(boolean test,String message){if(!test)throw new AssertionError(message);}
    private static boolean open(int port){try(Socket s=new Socket()){s.connect(new InetSocketAddress("127.0.0.1",port),500);return true;}catch(java.io.IOException e){return false;}}
}
