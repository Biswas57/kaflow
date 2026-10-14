package tributary.stream;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import kaflow.broker.AppendCommand;
import kaflow.groups.GroupId;
import kaflow.groups.JoinCommand;
import kaflow.groups.MembershipView;
import kaflow.groups.SessionIdentity;
import kaflow.v1.JoinRequest;
import kaflow.v1.HeartbeatRequest;
import kaflow.v1.LeaveRequest;
import kaflow.v1.LeaveResponse;
import kaflow.v1.MembershipResponse;
import kaflow.broker.ImmutableBytes;
import kaflow.broker.TopicId;
import kaflow.storage.DurableBroker;
import kaflow.storage.DurableException;
import kaflow.storage.DurableOptions;
import kaflow.v1.AppendRequest;
import kaflow.v1.AppendResponse;
import kaflow.v1.BrokerGrpc;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@GrpcService
@ConditionalOnProperty(name = "kaflow.durable.enabled", havingValue = "true")
public final class DurableAppendService extends BrokerGrpc.BrokerImplBase {
    private final DurableBroker broker;
    private final DurableOptions options;

    public DurableAppendService(DurableBroker broker, DurableOptions options) {
        this.broker = broker; this.options = options;
    }

    @Override public void append(AppendRequest request, StreamObserver<AppendResponse> output) {
        AppendCommand command;
        try {
            if (request.getPayload().size() > options.storage().maxPayloadBytes()
                    || request.getKey().size() > options.storage().maxKeyBytes()) throw new IllegalArgumentException();
            command = new AppendCommand(new TopicId(request.getTopic()),
                    request.hasPartition() ? OptionalInt.of(request.getPartition()) : OptionalInt.empty(),
                    request.hasKey() ? Optional.of(ImmutableBytes.copyOf(request.getKey().toByteArray())) : Optional.empty(),
                    ImmutableBytes.copyOf(request.getPayload().toByteArray()));
        } catch (IllegalArgumentException e) {
            output.onError(Status.INVALID_ARGUMENT.withDescription("Invalid append request").asRuntimeException()); return;
        }
        var result = broker.append(command);
        ServerCallStreamObserver<AppendResponse> call = output instanceof ServerCallStreamObserver<?>
                ? (ServerCallStreamObserver<AppendResponse>) output : null;
        if (call != null) call.setOnCancelHandler(() -> result.cancel(false));
        result.whenComplete((receipt, error) -> {
            if (call != null && call.isCancelled()) return;
            if (error != null) { output.onError(status(error).asRuntimeException()); return; }
            output.onNext(AppendResponse.newBuilder().setPartition(kaflow.v1.TopicPartition.newBuilder()
                    .setTopic(receipt.partition().topic().value()).setPartition(receipt.partition().partition()))
                    .setOffset(receipt.offset()).setTimestampMillis(receipt.timestampMillis()).build());
            output.onCompleted();
        });
    }

    @Override public void join(JoinRequest request, StreamObserver<MembershipResponse> output) {
        try {
            var command = new JoinCommand(new GroupId(request.getGroup()), new TopicId(request.getTopic()), request.getMemberLabel());
            membershipResponse(broker.join(command), output, DurableAppendService::wire);
        } catch (IllegalArgumentException e) { invalidMembership(output); }
    }

    @Override public void heartbeat(HeartbeatRequest request, StreamObserver<MembershipResponse> output) {
        try { membershipResponse(broker.heartbeat(identity(request.getSession())), output, DurableAppendService::wire); }
        catch (IllegalArgumentException e) { invalidMembership(output); }
    }

    @Override public void leave(LeaveRequest request, StreamObserver<LeaveResponse> output) {
        try { membershipResponse(broker.leave(identity(request.getSession())), output, ignored -> LeaveResponse.getDefaultInstance()); }
        catch (IllegalArgumentException e) { invalidMembership(output); }
    }

    private static SessionIdentity identity(kaflow.v1.SessionIdentity identity) {
        return new SessionIdentity(new GroupId(identity.getGroup()), identity.getIncarnation(), identity.getSessionToken());
    }
    private static MembershipResponse wire(MembershipView view) {
        var identity = view.identity();
        var response = MembershipResponse.newBuilder().setSession(kaflow.v1.SessionIdentity.newBuilder()
                .setGroup(identity.group().value()).setIncarnation(identity.incarnation()).setSessionToken(identity.session()))
                .setGeneration(view.generation()).setLeaseDurationMillis(view.leaseMillis());
        for (var p : view.assignments()) response.addAssignments(kaflow.v1.TopicPartition.newBuilder()
                .setTopic(p.topic().value()).setPartition(p.partition()));
        return response.build();
    }
    private static void invalidMembership(StreamObserver<?> output) {
        output.onError(Status.INVALID_ARGUMENT.withDescription("Invalid membership request").asRuntimeException());
    }
    private static <T, R> void membershipResponse(java.util.concurrent.CompletableFuture<T> future, StreamObserver<R> output,
                                                  java.util.function.Function<T, R> convert) {
        ServerCallStreamObserver<?> call = output instanceof ServerCallStreamObserver<?> server ? server : null;
        if (call != null) call.setOnCancelHandler(() -> future.cancel(false));
        future.whenComplete((value, error) -> {
            if (call != null && call.isCancelled()) return;
            if (error != null) { output.onError(status(error).asRuntimeException()); return; }
            output.onNext(convert.apply(value)); output.onCompleted();
        });
    }

    private static Status status(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof DurableException e) {
            return switch (e.code()) {
                case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT.withDescription("Invalid append request");
                case NOT_FOUND -> Status.NOT_FOUND.withDescription("Unknown topic");
                case ALREADY_EXISTS -> Status.ALREADY_EXISTS.withDescription("Topic exists");
                case RESOURCE_EXHAUSTED -> Status.RESOURCE_EXHAUSTED.withDescription("Broker capacity exhausted");
                case UNAVAILABLE -> Status.UNAVAILABLE.withDescription("Broker unavailable; operation outcome may be unknown");
                case FENCED -> Status.FAILED_PRECONDITION.withDescription("Session or ownership is fenced");
                case BINDING_CONFLICT -> Status.FAILED_PRECONDITION.withDescription("Group topic binding conflicts");
                case INTERNAL -> Status.INTERNAL.withDescription("Broker operation failed; outcome may be unknown");
            };
        }
        return Status.INTERNAL.withDescription("Broker operation failed; outcome may be unknown");
    }
}
