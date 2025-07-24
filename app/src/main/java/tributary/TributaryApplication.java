package tributary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import tributary.api.TributaryController;

import java.util.logging.Logger;

@SpringBootApplication
public class TributaryApplication {

    private static final Logger logger = Logger.getLogger(TributaryApplication.class.getName());

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(TributaryApplication.class, args);
        
        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Tributary application");
            context.close();
        }));
    }

    /**
     * Create a shared TributaryController bean that will be used by both 
     * the REST API and gRPC services to ensure consistent state.
     */
    @Bean
    public TributaryController tributaryController() {
        return new TributaryController();
    }
}