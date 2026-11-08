package kaflow.clients;

import java.util.concurrent.CompletableFuture;
import kaflow.v1.*;

/** Small testable seam for the example's public protocol, not a storage adapter. */
interface ClientRpc {
    MembershipResponse join(JoinRequest request);
    MembershipResponse heartbeat(HeartbeatRequest request);
    void leave(LeaveRequest request);
    CompletableFuture<FetchResponse> fetch(FetchRequest request);
    CommitResponse commit(CommitRequest request);
}
