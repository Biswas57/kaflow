package kaflow.storage;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import kaflow.v1.AppendRequest;
import kaflow.v1.BrokerGrpc;
import kaflow.v1.*;

/** Offline fixture and separate real-network client for packaged smoke; never a public API. */
public final class DurableSmokeClient {
    private static final TopicId TOPIC = new TopicId("durable-smoke");
    public static void main(String[] args) throws Exception {
        switch(args[0]) {
            case "seed" -> {
                try(var broker=DurableBroker.open(Path.of(args[1]),DurableOptions.defaults(),Clock.systemUTC())) {
                    broker.createTopic(TOPIC,1).get(10,TimeUnit.SECONDS);
                }
                System.out.println("SEEDED");
            }
            case "append" -> {
                int port=Integer.parseInt(args[1]), expected=Integer.parseInt(args[3]);
                var channel=ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build();
                try {
                    var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS);
                    try(var file=new FileOutputStream(args[2],true); var out=new DataOutputStream(file)) {
                        for(int i=0;i<2;i++) {
                            byte[] payload=i==0?new byte[]{0,-1,-128,-61,40}:new byte[0];
                            var request=AppendRequest.newBuilder().setTopic(TOPIC.value()).setPartition(0).setPayload(ByteString.copyFrom(payload));
                            if(i==0)request.setKey(ByteString.EMPTY);
                            var receipt=stub.append(request.build());
                            if(receipt.getOffset()!=expected+i||receipt.getPartition().getPartition()!=0
                                    ||!receipt.getPartition().getTopic().equals(TOPIC.value()))throw new AssertionError("incorrect network receipt");
                            out.writeLong(receipt.getOffset());out.writeLong(receipt.getTimestampMillis());out.writeBoolean(i==0);
                            out.writeInt(payload.length);out.write(payload);out.flush();file.getChannel().force(true);
                        }
                    }
                } finally { if(!channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("channel shutdown"); }
                System.out.println("ACKNOWLEDGED "+expected+" "+(expected+1));
            }
            case "consume" -> {
                int port=Integer.parseInt(args[1]), phase=Integer.parseInt(args[3]);
                var channel=ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build();
                try {
                    var stub=BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS);
                    var partition=kaflow.v1.TopicPartition.newBuilder().setTopic(TOPIC.value()).setPartition(0).build();
                    java.util.Map<Long,kaflow.v1.Record> expected=new java.util.HashMap<>();
                    try(var input=new DataInputStream(new FileInputStream(args[2]))) {
                        for(;;) {
                            long offset;try{offset=input.readLong();}catch(EOFException end){break;}
                            long timestamp=input.readLong();boolean key=input.readBoolean();int length=input.readInt();
                            if(length<0||length>100)throw new AssertionError("witness length");
                            var record=kaflow.v1.Record.newBuilder().setPartition(partition).setOffset(offset).setTimestampMillis(timestamp).setPayload(ByteString.copyFrom(input.readNBytes(length)));
                            if(key)record.setKey(ByteString.EMPTY);expected.put(offset,record.build());
                        }
                    }
                    for(int group=0;group<2;group++) {
                        long start=phase==0?0:(group==0?2:1), target=phase*2+(group==0?2:1);
                        var member=stub.join(JoinRequest.newBuilder().setGroup("smoke-group-"+group).setTopic(TOPIC.value()).build());
                        if(!member.getAssignmentsList().equals(java.util.List.of(partition))||!member.getCommittedNextOffsetsList().equals(java.util.List.of(start)))throw new AssertionError("incorrect resumed progress");
                        var request=FetchRequest.newBuilder().setSession(member.getSession()).setGeneration(member.getGeneration()).setPartition(partition).setStartOffset(start).setMaxRecords(128).setMaxBytes(128);
                        var batch=stub.fetch(request.build());long end=phase*2+2;
                        if(batch.getPublishedEnd()!=end||batch.getNextOffset()!=end||batch.getRecordsCount()!=end-start)throw new AssertionError("consumer boundary");
                        for(var record:batch.getRecordsList())if(!record.equals(expected.get(record.getOffset())))throw new AssertionError("public record changed");
                        if(stub.heartbeat(HeartbeatRequest.newBuilder().setSession(member.getSession()).build()).getCommittedNextOffsets(0)!=start)throw new AssertionError("fetch committed progress");
                        var commit=CommitRequest.newBuilder().setSession(member.getSession()).setGeneration(member.getGeneration()).setPartition(partition).setNextOffset(target);
                        if(stub.commit(commit.build()).getDurableNextOffset()!=target)throw new AssertionError("commit result");
                        if(stub.commit(commit.setNextOffset(0).build()).getDurableNextOffset()!=target)throw new AssertionError("commit regressed");
                        var replay=stub.fetch(request.setStartOffset(0).build());
                        if(replay.getRecordsCount()!=end||stub.heartbeat(HeartbeatRequest.newBuilder().setSession(member.getSession()).build()).getCommittedNextOffsets(0)!=target)throw new AssertionError("replay changed progress");
                    }
                } finally {if(!channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("channel shutdown");}
                System.out.println("CONSUMED two independent groups; resumed="+(phase==0?"0,0":"2,1")+" committed="+(phase==0?"2,1":"4,3"));
            }
            case "inspect" -> {
                int count=0;
                try(var broker=DurableBroker.open(Path.of(args[1]),DurableOptions.defaults(),Clock.systemUTC());
                    var input=new DataInputStream(new FileInputStream(args[2]))) {
                    for(;;) {
                        long offset;try{offset=input.readLong();}catch(EOFException end){break;}
                        long timestamp=input.readLong();boolean key=input.readBoolean();int length=input.readInt();
                        if(length<0||length>100)throw new AssertionError("witness length");byte[] payload=input.readNBytes(length);
                        var record=broker.inspect(new TopicPartition(TOPIC,0),offset,1,100).get(10,TimeUnit.SECONDS).get(0);
                        if(record.offset()!=offset||record.timestampMillis()!=timestamp||record.key().isPresent()!=key
                                ||!Arrays.equals(record.payload().toByteArray(),payload)||record.key().isPresent()&&record.key().get().length()!=0)
                            throw new AssertionError("acknowledged record changed on restart");
                        count++;
                    }
                    if(count!=4||broker.diagnostics().published().get(new TopicPartition(TOPIC,0))!=4)throw new AssertionError("recovered count");
                }
                System.out.println("VERIFIED "+count+" acknowledged records");
            }
            default -> throw new IllegalArgumentException("unknown fixture operation");
        }
    }
}
