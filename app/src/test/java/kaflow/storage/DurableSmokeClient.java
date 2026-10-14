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
