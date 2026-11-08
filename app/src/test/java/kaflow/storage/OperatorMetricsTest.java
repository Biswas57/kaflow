package kaflow.storage;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tributary.config.BrokerMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static kaflow.storage.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class OperatorMetricsTest {
    @TempDir Path directory;
    @Test void overloadWatchdogAndLateDiskFailureHaveDistinctObservableEffects() throws Exception {
        var disk = new Events(); var time = new AtomicLong(); var degraded = new CountDownLatch(1);
        var options = options(160, 1, 2097152);
        var broker = start(directory, options, time, disk, e -> { if (e.equals("state-DEGRADED")) degraded.countDown(); });
        var registry = new SimpleMeterRegistry();
        try {
            new BrokerMetrics(registry, broker); int inventory = registry.getMeters().size();
            await(broker.createTopic(TOPIC, 1)); broker.awaitIdle(WAIT);
            disk.pause = "data-written"; disk.fail = "data-written";
            var write = broker.append(command((byte) 42)); assertTrue(disk.entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, registry.get("kaflow.writer.active").gauge().value());
            assertEquals(1, registry.get("kaflow.admission.bytes").gauge().value());
            failure(DurableException.Code.RESOURCE_EXHAUSTED, broker.append(command()));
            assertEquals(1, registry.get("kaflow.accepting").gauge().value());
            assertEquals(0, registry.get("kaflow.storage.failures").functionCounter().count());
            time.set(options.watchdog().toNanos()); broker.pulse(); assertTrue(degraded.await(5, TimeUnit.SECONDS));
            failure(DurableException.Code.UNAVAILABLE, write);
            assertEquals(1, registry.get("kaflow.watchdog.events").functionCounter().count());
            assertEquals(0, registry.get("kaflow.accepting").gauge().value());
            assertEquals(1, registry.get("kaflow.admission.calls").gauge().value());
            disk.proceed.countDown(); broker.awaitIdle(WAIT);
            assertEquals(1, registry.get("kaflow.storage.failures").functionCounter().count());
            assertEquals(0, registry.get("kaflow.admission.calls").gauge().value());
            assertEquals(0, registry.get("kaflow.accepting").gauge().value());
            assertEquals(inventory, registry.getMeters().size());
            assertTrue(broker.shutdown(WAIT));
            assertEquals(1, registry.get("kaflow.state").tag("state", "CLOSED").gauge().value());
        } finally { disk.proceed.countDown(); assertTrue(broker.shutdown(WAIT)); registry.close(); }
    }
}
