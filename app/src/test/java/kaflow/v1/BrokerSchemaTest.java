package kaflow.v1;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrokerSchemaTest {
    @Test
    void optionalKeyAndPartitionPresenceSurviveWireRoundTrip() throws Exception {
        AppendRequest absent = AppendRequest.parseFrom(AppendRequest.newBuilder().setTopic("orders").build().toByteArray());
        assertFalse(absent.hasPartition());
        assertFalse(absent.hasKey());
        assertTrue(absent.getPayload().isEmpty());
        AppendRequest present = AppendRequest.parseFrom(absent.toBuilder()
                .setPartition(0).setKey(ByteString.EMPTY).build().toByteArray());
        assertTrue(present.hasPartition());
        assertEquals(0, present.getPartition());
        assertTrue(present.hasKey());
        assertTrue(present.getKey().isEmpty());
        assertNotEquals(absent, present);
    }

    @Test
    void opaqueBinaryPayloadAndKeyRemainExact() throws Exception {
        byte[] value = {0, -1, -128, -61, 40};
        AppendRequest request = AppendRequest.newBuilder().setTopic("orders").setPartition(2)
                .setKey(ByteString.copyFrom(value)).setPayload(ByteString.copyFrom(value)).build();
        AppendRequest decoded = AppendRequest.parseFrom(request.toByteArray());
        assertEquals(request, decoded);
        assertArrayEquals(value, decoded.getPayload().toByteArray());
        assertArrayEquals(value, decoded.getKey().toByteArray());
    }

    @Test
    void schemaPreservesSingleRecordAppendAndAddsOnlyUnaryMembership() throws Exception {
        assertEquals("kaflow.v1.Broker", BrokerGrpc.SERVICE_NAME);
        assertEquals(List.of("Append", "Join", "Heartbeat", "Leave"), BrokerProtocol.getDescriptor()
                .findServiceByName("Broker").getMethods().stream().map(Descriptors.MethodDescriptor::getName).toList());
        assertEquals(4, BrokerGrpc.getServiceDescriptor().getMethods().size());
        assertTrue(BrokerGrpc.getServiceDescriptor().getMethods().stream().allMatch(m -> m.getType() == MethodDescriptor.MethodType.UNARY));
        assertEquals(MethodDescriptor.MethodType.UNARY, BrokerGrpc.getAppendMethod().getType());
        assertEquals(List.of("topic", "partition", "key", "payload"), AppendRequest.getDescriptor()
                .getFields().stream().map(Descriptors.FieldDescriptor::getName).toList());
        assertEquals(Descriptors.FieldDescriptor.Type.BYTES, AppendRequest.getDescriptor().findFieldByName("payload").getType());
        assertEquals(List.of("partition", "offset", "timestamp_millis"), AppendResponse.getDescriptor()
                .getFields().stream().map(Descriptors.FieldDescriptor::getName).toList());
        AppendResponse response = AppendResponse.newBuilder()
                .setPartition(TopicPartition.newBuilder().setTopic("orders").setPartition(6))
                .setOffset(Long.MAX_VALUE - 1).setTimestampMillis(123456789).build();
        assertEquals(response, AppendResponse.parseFrom(response.toByteArray()));
    }
}
