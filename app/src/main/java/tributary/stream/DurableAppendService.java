package tributary.stream;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import kaflow.broker.AppendCommand;
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

    private static Status status(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof DurableException e) {
            return switch (e.code()) {
                case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT.withDescription("Invalid append request");
                case NOT_FOUND -> Status.NOT_FOUND.withDescription("Unknown topic");
                case ALREADY_EXISTS -> Status.ALREADY_EXISTS.withDescription("Topic exists");
                case RESOURCE_EXHAUSTED -> Status.RESOURCE_EXHAUSTED.withDescription("Broker capacity exhausted");
                case UNAVAILABLE -> Status.UNAVAILABLE.withDescription("Broker unavailable; append outcome may be unknown");
                case INTERNAL -> Status.INTERNAL.withDescription("Append failed; outcome may be unknown");
            };
        }
        return Status.INTERNAL.withDescription("Append failed; outcome may be unknown");
    }
}
