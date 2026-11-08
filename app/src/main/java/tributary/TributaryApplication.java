package tributary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import tributary.api.TributaryController;
import tributary.core.tributaryObject.TributaryCluster;

@SpringBootApplication
public class TributaryApplication {

    public static void main(String[] args) {
        // Spring owns context shutdown and invokes the streaming service's cleanup.
        SpringApplication.run(TributaryApplication.class, args);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "kaflow.durable.enabled", havingValue = "false", matchIfMissing = true)
    public TributaryCluster tributaryCluster() {
        return new TributaryCluster();
    }

    /** One controller is shared by this application's REST and gRPC adapters. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "kaflow.durable.enabled", havingValue = "false", matchIfMissing = true)
    public TributaryController tributaryController(TributaryCluster cluster) {
        return new TributaryController(cluster);
    }
}
