package tributary.config;

import io.grpc.*;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;
import kaflow.storage.DurableBroker;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fixed meter inventory; user data never becomes a name, tag or observation label. */
@Component
@GrpcGlobalServerInterceptor
@ConditionalOnProperty(name = "kaflow.durable.enabled", havingValue = "true")
public final class BrokerMetrics implements ServerInterceptor {
    private static final String[] METHODS = {"Append", "Fetch", "Commit", "Join", "Heartbeat", "Leave"};
    private final Map<String, Timer[]> timers = new java.util.HashMap<>();
    private final Map<String, Counter[]> outcomes = new java.util.HashMap<>();
    private final Timer[] shutdownTimers = new Timer[4];
    private final Counter[] shutdownCounts = new Counter[4];

    public BrokerMetrics(MeterRegistry registry, DurableBroker broker) {
        for (String method : METHODS) {
            Timer[] times = new Timer[Status.Code.values().length]; Counter[] counts = new Counter[times.length];
            for (var code : Status.Code.values()) {
                var tags = Tags.of("method", method, "status", code.name());
                counts[code.ordinal()] = Counter.builder("kaflow.rpc.outcomes").tags(tags).register(registry);
                times[code.ordinal()] = timer(registry, "kaflow.rpc.duration", tags);
            }
            timers.put("kaflow.v1.Broker/" + method, times); outcomes.put("kaflow.v1.Broker/" + method, counts);
        }
        String[] close = {"complete", "incomplete", "interrupted", "failed"};
        for (int i = 0; i < close.length; i++) {
            shutdownCounts[i] = Counter.builder("kaflow.shutdown.outcomes").tag("outcome", close[i]).register(registry);
            shutdownTimers[i] = timer(registry, "kaflow.shutdown.duration", Tags.of("outcome", close[i]));
        }
        gauge(registry, broker, "admission.calls", b -> b.diagnostics().admittedCalls());
        gauge(registry, broker, "admission.bytes", b -> b.diagnostics().admittedBytes());
        gauge(registry, broker, "queue.mutations", b -> b.diagnostics().queuedCalls());
        gauge(registry, broker, "writer.active", b -> b.diagnostics().activeOperation() == 0 ? 0 : 1);
        gauge(registry, broker, "read.admitted", b -> b.consumerDiagnostics().reads());
        gauge(registry, broker, "read.bytes", b -> b.consumerDiagnostics().readBytes());
        gauge(registry, broker, "read.active", b -> b.consumerDiagnostics().activeReads());
        gauge(registry, broker, "progress.admitted", b -> b.consumerDiagnostics().progressCalls());
        gauge(registry, broker, "progress.bytes", b -> b.consumerDiagnostics().progressBytes());
        gauge(registry, broker, "progress.barriers", b -> b.consumerDiagnostics().barriers());
        gauge(registry, broker, "wait.parked", b -> b.waitDiagnostics().parked());
        gauge(registry, broker, "wait.admitted", b -> b.waitDiagnostics().admitted());
        gauge(registry, broker, "wait.bytes", b -> b.waitDiagnostics().metadataBytes());
        gauge(registry, broker, "wait.timers", b -> b.waitDiagnostics().timers());
        gauge(registry, broker, "wait.notifications", b -> b.waitDiagnostics().notifications());
        gauge(registry, broker, "groups", b -> b.membershipDiagnostics().groups());
        gauge(registry, broker, "sessions", b -> b.membershipDiagnostics().members());
        gauge(registry, broker, "control.admitted", b -> b.membershipDiagnostics().admittedControls());
        gauge(registry, broker, "control.bytes", b -> b.membershipDiagnostics().admittedBytes());
        gauge(registry, broker, "queue.controls", b -> b.membershipDiagnostics().queuedControls());
        gauge(registry, broker, "joins.pending", b -> b.membershipDiagnostics().pendingJoins());
        gauge(registry, broker, "queue.readers", b -> b.executorMetrics().readerQueue());
        gauge(registry, broker, "queue.writer", b -> b.executorMetrics().writerQueue());
        gauge(registry, broker, "queue.responses", b -> b.executorMetrics().responseQueue());
        gauge(registry, broker, "responses.active", b -> b.executorMetrics().responseActive());
        gauge(registry, broker, "queue.transport", b -> b.executorMetrics().transportQueue());
        gauge(registry, broker, "transport.active", b -> b.executorMetrics().transportActive());
        gauge(registry, broker, "storage.data.bytes", b -> b.storageMetrics().dataBytes());
        gauge(registry, broker, "storage.metadata.bytes", b -> b.storageMetrics().metadataBudget());
        gauge(registry, broker, "storage.reserved.bytes", b -> b.storageMetrics().reservedBytes());
        gauge(registry, broker, "storage.quota.bytes", b -> b.storageMetrics().quotaBytes());
        gauge(registry, broker, "storage.available.bytes", b -> b.storageMetrics().availableBytes());
        gauge(registry, broker, "storage.pending.deletion", b -> b.storageMetrics().pendingDeletion());
        gauge(registry, broker, "storage.pins", b -> b.storageMetrics().pins());
        gauge(registry, broker, "accepting", b -> b.diagnostics().ready() ? 1 : 0);
        for (var state : DurableBroker.State.values()) Gauge.builder("kaflow.state", broker,
                b -> b.diagnostics().state() == state ? 1 : 0).tag("state", state.name()).register(registry);
        FunctionCounter.builder("kaflow.storage.failures", broker, DurableBroker::storageFailureCount).register(registry);
        FunctionCounter.builder("kaflow.watchdog.events", broker, DurableBroker::watchdogCount).register(registry);
    }
    private static Timer timer(MeterRegistry registry, String name, Tags tags) {
        return Timer.builder(name).tags(tags).serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(10),
                Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30)).register(registry);
    }
    private static void gauge(MeterRegistry registry, DurableBroker broker, String name, ToDoubleFunction<DurableBroker> value) {
        Gauge.builder("kaflow." + name, broker, value).register(registry);
    }
    public void shutdown(int outcome, long elapsedNanos) {
        shutdownCounts[outcome].increment(); shutdownTimers[outcome].record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }
    @Override public <Req, Resp> ServerCall.Listener<Req> interceptCall(ServerCall<Req, Resp> call,
            Metadata headers, ServerCallHandler<Req, Resp> next) {
        String name = call.getMethodDescriptor().getFullMethodName(); var times = timers.get(name);
        if (times == null) return next.startCall(call, headers);
        var counts = outcomes.get(name); var finished = new AtomicBoolean(); long began = System.nanoTime();
        java.util.function.Consumer<Status> finish = status -> {
            if (finished.compareAndSet(false, true)) {
                int index = status.getCode().ordinal(); counts[index].increment();
                times[index].record(Math.max(0, System.nanoTime() - began), TimeUnit.NANOSECONDS);
            }
        };
        var observed = new ForwardingServerCall.SimpleForwardingServerCall<Req, Resp>(call) {
            @Override public void close(Status status, Metadata trailers) { finish.accept(status); super.close(status, trailers); }
        };
        try {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<Req>(next.startCall(observed, headers)) {
                @Override public void onCancel() {
                    finish.accept(Context.current().getDeadline() != null && Context.current().getDeadline().isExpired()
                            ? Status.DEADLINE_EXCEEDED : Status.CANCELLED);
                    super.onCancel();
                }
            };
        } catch (RuntimeException error) { finish.accept(Status.INTERNAL); throw error; }
    }
}
