package tributary.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import kaflow.storage.DurableBroker;
import kaflow.groups.GroupOptions;
import kaflow.consumer.ConsumerOptions;
import kaflow.storage.DurableOptions;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit durable namespace; failed recovery aborts startup and never selects a volatile fallback. */
@Configuration
@ConditionalOnProperty(name = "kaflow.durable.enabled", havingValue = "true")
public class DurableBrokerConfiguration {
    @Bean
    public DurableOptions durableOptions() { return DurableOptions.defaults(); }

    @Bean
    public GroupOptions groupOptions() { return GroupOptions.defaults(); }

    @Bean
    public ConsumerOptions consumerOptions() { return ConsumerOptions.defaults(); }

    @Bean(destroyMethod = "close")
    public DurableBroker durableBroker(@Value("${kaflow.durable.directory}") String directory,
                                       DurableOptions options, GroupOptions groups, ConsumerOptions consumers) throws IOException {
        if (directory.isBlank()) throw new IllegalArgumentException("durable directory is required");
        return DurableBroker.open(Path.of(directory), options, groups, consumers, Clock.systemUTC());
    }

    @Bean
    public GrpcServerConfigurer durableInboundLimit(DurableOptions options, ThreadPoolExecutor durableTransportExecutor) {
        // Transport buffering precedes application admission. Both have independent limits.
        int limit = Math.addExact(1024, options.storage().maxKeyBytes() + options.storage().maxPayloadBytes());
        return builder -> builder.maxInboundMessageSize(limit).executor(durableTransportExecutor);
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor durableTransportExecutor() {
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "kaflow-transport"); thread.setDaemon(true); return thread;
        });
    }

    @Bean
    public HealthIndicator durableBrokerHealthIndicator(DurableBroker broker) {
        return () -> broker.diagnostics().ready() ? Health.up().build() : Health.outOfService().build();
    }
}
