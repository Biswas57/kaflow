package tributary.stream;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kaflow.v1.AppendRequest;
import kaflow.v1.BrokerGrpc;
import org.junit.jupiter.api.Test;
import tributary.api.TributaryController;
import tributary.core.tributaryObject.TributaryCluster;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class V1NetworkBoundaryTest {
    @Test
    void standalonePrototypeDoesNotExposeV1Append() throws Exception {
        try (TributaryStreamServer server = new TributaryStreamServer(0,
                new TributaryController(new TributaryCluster()))) {
            server.start();
            ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            try {
                assertUnimplemented(channel);
            } finally {
                assertTrue(channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    static void assertUnimplemented(ManagedChannel channel) {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .append(AppendRequest.newBuilder().setTopic("orders").build()));
        assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
        var stub = BrokerGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
        assertEquals(Status.Code.UNIMPLEMENTED, assertThrows(StatusRuntimeException.class,
                () -> stub.fetch(kaflow.v1.FetchRequest.getDefaultInstance())).getStatus().getCode());
        assertEquals(Status.Code.UNIMPLEMENTED, assertThrows(StatusRuntimeException.class,
                () -> stub.commit(kaflow.v1.CommitRequest.getDefaultInstance())).getStatus().getCode());
        assertEquals(Status.Code.UNIMPLEMENTED, assertThrows(StatusRuntimeException.class,
                () -> stub.join(kaflow.v1.JoinRequest.getDefaultInstance())).getStatus().getCode());
        assertEquals(Status.Code.UNIMPLEMENTED, assertThrows(StatusRuntimeException.class,
                () -> stub.heartbeat(kaflow.v1.HeartbeatRequest.getDefaultInstance())).getStatus().getCode());
        assertEquals(Status.Code.UNIMPLEMENTED, assertThrows(StatusRuntimeException.class,
                () -> stub.leave(kaflow.v1.LeaveRequest.getDefaultInstance())).getStatus().getCode());
    }
}
