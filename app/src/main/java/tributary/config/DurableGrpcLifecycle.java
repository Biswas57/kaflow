package tributary.config;

import io.grpc.Server;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import kaflow.storage.DurableBroker;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import net.devh.boot.grpc.server.serverfactory.GrpcServerFactory;
import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

/** One application shutdown budget, shared by listener stop, broker drain and repeated destruction. */
final class DurableGrpcLifecycle extends GrpcServerLifecycle implements ApplicationListener<GrpcServerStartedEvent>, DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(DurableGrpcLifecycle.class);
    private final long budget;
    private final DurableBroker broker;
    private final BrokerMetrics metrics;
    private boolean outcomeReported;
    private Server server;
    private boolean stopping, complete;
    private long began;

    DurableGrpcLifecycle(GrpcServerFactory factory, ApplicationEventPublisher publisher, DurableBroker broker, Duration timeout, BrokerMetrics metrics) {
        super(factory, Duration.ZERO, publisher); this.broker = broker; this.budget = timeout.toNanos(); this.metrics = metrics;
    }
    @Override public void onApplicationEvent(GrpcServerStartedEvent event) {
        if (event.getSource() == this) { server = event.getServer(); broker.ownTransportLifecycle(server::shutdown, server::isTerminated); }
    }
    @Override public synchronized void stop() {
        if (complete) return;
        if (!stopping) { stopping = true; began = System.nanoTime(); }
        if (server != null) server.shutdown(); // Stop new RPCs before draining the owned dispatch executor.
        broker.requestClose();
        int outcome = 1;
        try {
            boolean brokerDone = broker.shutdown(Duration.ofNanos(remaining()));
            boolean networkDone = server == null || server.awaitTermination(remaining(), TimeUnit.NANOSECONDS);
            if (brokerDone && networkDone) {
                super.stop(); complete = true; outcome = 0;
            } else LOG.warn("Durable shutdown incomplete; broker state={}, network terminated={}; live ownership is retained until actual work finishes",
                    broker.diagnostics().state(), networkDone);
        } catch (InterruptedException e) {
            outcome = 2; Thread.currentThread().interrupt(); LOG.warn("Durable shutdown interrupted; cleanup remains pending");
        } catch (java.io.IOException e) {
            outcome = 3; LOG.error("Durable shutdown failed", e);
        } finally {
            if (!outcomeReported) { outcomeReported = true; metrics.shutdown(outcome, System.nanoTime() - began); }
        }
    }
    private long remaining() { return Math.max(0, budget - (System.nanoTime() - began)); }
    @Override public void destroy() { stop(); } // Never grants a second shutdown budget.
}
