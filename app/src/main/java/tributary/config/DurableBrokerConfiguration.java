package tributary.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.*;
import kaflow.storage.*;
import kaflow.groups.GroupOptions;
import kaflow.consumer.ConsumerOptions;
import kaflow.consumer.WaitOptions;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Recovery and full-list preflight finish before the V1 listener can be constructed. */
@Configuration
@ConditionalOnProperty(name = "kaflow.durable.enabled", havingValue = "true")
public class DurableBrokerConfiguration {
    @Bean public DurableOptions durableOptions(DurableProperties p) { return p.durableOptions(); }
    @Bean public GroupOptions groupOptions(DurableProperties p) { return p.groupOptions(); }
    @Bean public ConsumerOptions consumerOptions(DurableProperties p) { return p.consumerOptions(); }
    @Bean public WaitOptions waitOptions(DurableProperties p) { return p.waitOptions(); }
    @Bean public LifecycleOptions lifecycleOptions(DurableProperties p) { return p.lifecycleOptions(); }

    @Bean(destroyMethod = "closeFromContainer")
    public DurableBroker durableBroker(DurableProperties p, DurableOptions options, GroupOptions groups,
            ConsumerOptions consumers, WaitOptions waits, LifecycleOptions lifecycle,
            ThreadPoolExecutor durableTransportExecutor) throws IOException {
        var topics = p.provisionedTopics(options); // Syntax checks precede directory creation/open.
        var broker = DurableBroker.open(Path.of(p.directory()), options, groups, consumers, waits, lifecycle, Clock.systemUTC());
        broker.ownTransportExecutor(durableTransportExecutor, p.shutdownTimeout());
        long began = System.nanoTime(), budget = options.watchdog().toNanos();
        try {
            broker.preflightTopics(new ProvisioningPlan(topics)).get(remaining(began, budget), TimeUnit.NANOSECONDS);
            for (var topic : topics.entrySet())
                broker.ensureTopic(topic.getKey(), topic.getValue()).get(remaining(began, budget), TimeUnit.NANOSECONDS);
            return broker;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); broker.requestClose();
            throw new IOException("startup provisioning interrupted; cleanup pending", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            broker.requestClose();
            try { if (!broker.shutdown(p.shutdownTimeout())) e.addSuppressed(new IOException("startup cleanup incomplete; ownership retained")); }
            catch (Exception cleanup) { if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt(); e.addSuppressed(cleanup); }
            throw new IOException("startup provisioning failed; no volatile fallback", e);
        }
    }
    private static long remaining(long began, long budget) { return Math.max(0, budget - (System.nanoTime() - began)); }

    @Bean public static org.springframework.beans.factory.config.BeanPostProcessor durableGrpcLifecycleProcessor() {
        return new DurableGrpcLifecycleProcessor();
    }
    @Bean public GrpcServerConfigurer durableInboundLimit(DurableOptions options, ThreadPoolExecutor durableTransportExecutor) {
        int limit = Math.addExact(1024, options.storage().maxKeyBytes() + options.storage().maxPayloadBytes());
        return builder -> builder.maxInboundMessageSize(limit).executor(durableTransportExecutor);
    }
    @Bean(destroyMethod = "shutdown") public ThreadPoolExecutor durableTransportExecutor() {
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "kaflow-transport"); thread.setDaemon(true); return thread;
        });
    }
}
