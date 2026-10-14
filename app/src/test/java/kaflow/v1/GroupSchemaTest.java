package kaflow.v1;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GroupSchemaTest {
    @Test void membershipCredentialsGenerationAndQualifiedAssignmentsRoundTrip() throws Exception {
        var session=SessionIdentity.newBuilder().setGroup("group").setIncarnation("8f1a4d08-0ee2-4fc8-984a-f1ef989e7f16")
                .setSessionToken("e313b814-105a-4661-9c5a-d001af56e008").build();
        var response=MembershipResponse.newBuilder().setSession(session).setGeneration(Long.MAX_VALUE).setLeaseDurationMillis(30000)
                .addAssignments(TopicPartition.newBuilder().setTopic("orders").setPartition(2)).build();
        assertEquals(response,MembershipResponse.parseFrom(response.toByteArray()));
        var heartbeat=HeartbeatRequest.newBuilder().setSession(session).build();assertEquals(heartbeat,HeartbeatRequest.parseFrom(heartbeat.toByteArray()));
        var leave=LeaveRequest.newBuilder().setSession(session).build();assertEquals(leave,LeaveRequest.parseFrom(leave.toByteArray()));
        assertEquals(List.of("session"),HeartbeatRequest.getDescriptor().getFields().stream().map(f->f.getName()).toList());
        assertEquals(List.of("group","topic","member_label"),JoinRequest.getDescriptor().getFields().stream().map(f->f.getName()).toList());
        assertEquals(0,LeaveResponse.getDefaultInstance().getSerializedSize());
    }

    @ParameterizedTest @ValueSource(ints={1,64})
    void conservativeResponseAllowanceFitsMaximalIdentifiersAndFencingNumbers(int count) {
        var session=SessionIdentity.newBuilder().setGroup("g".repeat(255)).setIncarnation("8f1a4d08-0ee2-4fc8-984a-f1ef989e7f16")
                .setSessionToken("e313b814-105a-4661-9c5a-d001af56e008");
        var response=MembershipResponse.newBuilder().setSession(session).setGeneration(Long.MAX_VALUE).setLeaseDurationMillis(300000);
        for(int p=0;p<count;p++) { response.addAssignments(TopicPartition.newBuilder().setTopic("t".repeat(255)).setPartition(p)); response.addCommittedNextOffsets(Long.MAX_VALUE); }
        assertTrue(response.build().getSerializedSize() <= 512L+count*(255L+16));
    }
}
