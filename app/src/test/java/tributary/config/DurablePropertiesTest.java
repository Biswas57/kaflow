package tributary.config;

import java.util.Map;
import kaflow.storage.*;
import kaflow.groups.GroupOptions;
import kaflow.consumer.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class DurablePropertiesTest {
    private DurableProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("kaflow.durable", Bindable.of(DurableProperties.class));
    }
    @Test void defaultsMatchCoreAndWaitPropertiesBindWithoutAliases() {
        var p = bind(Map.of());
        assertEquals(DurableOptions.defaults(), p.durableOptions());
        assertEquals(GroupOptions.defaults(), p.groupOptions());
        assertEquals(ConsumerOptions.defaults(), p.consumerOptions());
        assertEquals(WaitOptions.defaults(), p.waitOptions());
        assertEquals(LifecycleOptions.defaults(), p.lifecycleOptions());
        assertEquals(4, bind(Map.of("kaflow.durable.wait.batch", "4")).waitOptions().batch());
    }
    @Test void syntaxValidationRejectsDuplicateAndMalformedDefinitionsAndMissingDirectory() {
        assertThrows(IllegalArgumentException.class, () -> bind(Map.of()).provisionedTopics(DurableOptions.defaults()));
        var p = bind(Map.of("kaflow.durable.directory", "/unused", "kaflow.durable.topics[0].name", "same",
                "kaflow.durable.topics[0].partitions", "1", "kaflow.durable.topics[1].name", "same",
                "kaflow.durable.topics[1].partitions", "1"));
        assertThrows(IllegalArgumentException.class, () -> p.provisionedTopics(DurableOptions.defaults()));
        var malformed = bind(Map.of("kaflow.durable.directory", "/unused", "kaflow.durable.topics[0].name", "\ud800",
                "kaflow.durable.topics[0].partitions", "1"));
        assertThrows(IllegalArgumentException.class, () -> malformed.provisionedTopics(DurableOptions.defaults()));
    }
    @Test void invalidShutdownAndCoreBoundsReject() {
        assertThrows(RuntimeException.class, () -> bind(Map.of("kaflow.durable.shutdown-timeout", "0s")));
        assertThrows(IllegalArgumentException.class, () -> bind(Map.of("kaflow.durable.consumer.read-workers", "5")).consumerOptions());
    }
}
