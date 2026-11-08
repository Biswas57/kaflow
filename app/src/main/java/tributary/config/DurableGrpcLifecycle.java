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
    private static final long BUDGET = Duration.ofSeconds(10).toNanos();
    private final DurableBroker broker;
    private Server server;
    private boolean stopping, complete;
    private long began;

    DurableGrpcLifecycle(GrpcServerFactory factory, ApplicationEventPublisher publisher, DurableBroker broker) {
        super(factory, Duration.ZERO, publisher); this.broker = broker;
    }
    @Override public void onApplicationEvent(GrpcServerStartedEvent event) {
        if (event.getSource() == this) { server = event.getServer(); broker.ownTransportLifecycle(server::shutdown, server::isTerminated); }
    }
    @Override public synchronized void stop() {
        if (complete) return;
        if (!stopping) { stopping = true; began = System.nanoTime(); }
        if (server != null) server.shutdown(); // Stop new RPCs before draining the owned dispatch executor.
        broker.requestClose();
        try {
            boolean brokerDone = broker.shutdown(Duration.ofNanos(remaining()));
            boolean networkDone = server == null || server.awaitTermination(remaining(), TimeUnit.NANOSECONDS);
            if (brokerDone && networkDone) {
                super.stop(); complete = true;
            } else LOG.warn("Durable shutdown incomplete; broker state={}, network terminated={}; live ownership is retained until actual work finishes",
                    broker.diagnostics().state(), networkDone);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); LOG.warn("Durable shutdown interrupted; cleanup remains pending");
        } catch (java.io.IOException e) {
            LOG.error("Durable shutdown failed", e);
        }
    }
    private long remaining() { return Math.max(0, BUDGET - (System.nanoTime() - began)); }
    @Override public void destroy() { stop(); } // Never grants a second ten-second budget.
}
