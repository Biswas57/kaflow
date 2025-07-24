package tributary.stream;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.logging.Logger;

import tributary.api.TributaryController;

/**
 * gRPC server for TributaryStream service.
 * This server hosts the streaming gRPC endpoints for producing and consuming
 * messages.
 */
public class TributaryStreamServer {

    private static final Logger logger = Logger.getLogger(TributaryStreamServer.class.getName());

    private final int port;
    private final Server server;
    private final TributaryStreamImpl streamService;

    public TributaryStreamServer(int port, TributaryController controller) {
        this.port = port;
        this.streamService = new TributaryStreamImpl(controller);
        this.server = ServerBuilder.forPort(port)
                .addService(streamService)
                .build();
    }

    /**
     * Start the gRPC server.
     */
    public void start() throws IOException {
        server.start();
        logger.info("TributaryStream gRPC server started on port " + port);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down gRPC server");
            TributaryStreamServer.this.stop();
        }));
    }

    /**
     * Stop the gRPC server.
     */
    public void stop() {
        if (server != null) {
            streamService.shutdown();
            server.shutdown();
            logger.info("TributaryStream gRPC server stopped");
        }
    }

    /**
     * Wait for the server to terminate.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main method to run the server standalone for testing.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 9090; // default port
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        TributaryController controller = new TributaryController();
        TributaryStreamServer server = new TributaryStreamServer(port, controller);

        server.start();
        server.blockUntilShutdown();
    }
}
