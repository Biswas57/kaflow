package tributary.stream;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;

import tributary.api.TributaryController;
import tributary.core.tributaryObject.TributaryCluster;

/**
 * gRPC server for TributaryStream service.
 * This server hosts the streaming gRPC endpoints for producing and consuming
 * messages.
 */
public class TributaryStreamServer implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(TributaryStreamServer.class.getName());

    private final Server server;
    private final TributaryStreamImpl streamService;

    public TributaryStreamServer(int port, TributaryController controller) {
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
        logger.info("TributaryStream gRPC server started on port " + getPort());
    }

    public int getPort() {
        return server.getPort();
    }

    /** The composition root owns shutdown; start() installs no global hooks. */
    public void stop() {
        try {
            streamService.shutdown();
        } finally {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gRPC server did not terminate");
                    }
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
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

        TributaryController controller = new TributaryController(new TributaryCluster());
        TributaryStreamServer server = new TributaryStreamServer(port, controller);

        Thread shutdown = new Thread(server::stop, "tributary-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try (server) {
            server.start();
            server.blockUntilShutdown();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // JVM shutdown has already begun.
            }
        }
    }
}
