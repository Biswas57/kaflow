package tributary.config;

import kaflow.storage.DurableBroker;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;

@Configuration
@EnableConfigurationProperties(DurableProperties.class)
public class OperatorConfiguration {
    @Bean public BrokerReadiness brokerReadinessHealthIndicator(ObjectProvider<DurableBroker> brokers) {
        return new BrokerReadiness(brokers);
    }
    static final class BrokerReadiness implements HealthIndicator, ApplicationListener<ApplicationEvent> {
        private final ObjectProvider<DurableBroker> brokers;
        private volatile boolean applicationReady, transportStarted;
        BrokerReadiness(ObjectProvider<DurableBroker> brokers) { this.brokers = brokers; }
        @Override public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof GrpcServerStartedEvent) transportStarted = true;
            if (event instanceof ApplicationReadyEvent) applicationReady = true;
            if (event instanceof ContextClosedEvent) applicationReady = false;
        }
        @Override public Health health() {
            var broker = brokers.getIfAvailable();
            boolean ready = applicationReady && transportStarted && (broker == null || broker.diagnostics().ready());
            return ready ? Health.up().build() : Health.outOfService().build();
        }
    }
}
