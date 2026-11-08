package kaflow.clients;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** JDK-only readiness probe; packaged separately for the JRE container. */
public final class HealthProbe {
    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("readiness URL required");
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                    HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) System.exit(1);
        } catch (Exception e) { System.err.println("readiness probe unavailable"); System.exit(1); }
    }
}
