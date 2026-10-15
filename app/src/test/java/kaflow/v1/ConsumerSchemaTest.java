package kaflow.v1;

import com.google.protobuf.ByteString;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerSchemaTest {
    @Test void additiveWaitFieldPreservesOmittedZeroAndSignedWireValues() throws Exception {
        var field = FetchRequest.getDescriptor().findFieldByName("max_wait_millis");
        assertEquals(7, field.getNumber());
        assertEquals(com.google.protobuf.Descriptors.FieldDescriptor.Type.INT64, field.getType());
        assertEquals(0, FetchRequest.parseFrom(new byte[0]).getMaxWaitMillis());
        for (long value : new long[] {-1, 0, 1, 30000, Long.MAX_VALUE}) {
            var request = FetchRequest.newBuilder().setMaxWaitMillis(value).build();
            assertEquals(value, FetchRequest.parseFrom(request.toByteArray()).getMaxWaitMillis());
        }
    }
    @Test void binaryRecordsAndPresenceRoundTripWithSignedBoundaries() throws Exception {
        var partition=TopicPartition.newBuilder().setTopic("orders").setPartition(3);
        var present=Record.newBuilder().setPartition(partition).setOffset(Long.MAX_VALUE-1).setTimestampMillis(-1).setKey(ByteString.EMPTY).setPayload(ByteString.copyFrom(new byte[]{0,-1,-128,-61,40})).build();
        var response=FetchResponse.newBuilder().addRecords(present).addRecords(present.toBuilder().clearKey()).setNextOffset(Long.MAX_VALUE).setPublishedEnd(Long.MAX_VALUE).build();
        var decoded=FetchResponse.parseFrom(response.toByteArray());assertEquals(response,decoded);assertTrue(decoded.getRecords(0).hasKey());assertFalse(decoded.getRecords(1).hasKey());
        assertEquals(List.of("session","generation","partition","start_offset","max_records","max_bytes","max_wait_millis"),FetchRequest.getDescriptor().getFields().stream().map(f->f.getName()).toList());
        assertEquals(List.of("session","generation","partition","next_offset"),CommitRequest.getDescriptor().getFields().stream().map(f->f.getName()).toList());
        var commit=CommitResponse.newBuilder().setDurableNextOffset(Long.MAX_VALUE).build();assertEquals(commit,CommitResponse.parseFrom(commit.toByteArray()));
        var negative=FetchRequest.newBuilder().setStartOffset(-1).setMaxBytes(-1).build();assertEquals(-1,FetchRequest.parseFrom(negative.toByteArray()).getStartOffset());
    }
}
