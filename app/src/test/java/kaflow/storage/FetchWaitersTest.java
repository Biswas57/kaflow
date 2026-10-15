package kaflow.storage;

import java.util.UUID;
import kaflow.broker.TopicPartition;
import kaflow.groups.*;
import org.junit.jupiter.api.Test;
import static kaflow.storage.DurableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class FetchWaitersTest {
    @Test void coalescedHotAndQuietPartitionsRotateWithoutDuplicateOrLeakedEntries() {
        var registry = new FetchWaiters<Integer>(); var session = new SessionIdentity(new GroupId("group"), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var quiet = new TopicPartition(TOPIC, 1);
        for (int i = 0; i < 64; i++) registry.add(i, PART, session, i);
        registry.add(64, quiet, session, 64);
        for (int i = 0; i < 1000; i++) registry.published(PART);
        registry.published(quiet); assertEquals(2, registry.notifications());
        var first = registry.notified(); assertEquals(PART, first.partition()); registry.remove(first.id()); registry.published(PART);
        var second = registry.notified(); assertEquals(quiet, second.partition()); registry.remove(second.id());
        for (int i = 1; i < 64; i++) { var entry = registry.maintenance(); registry.remove(entry.id()); registry.remove(entry.id()); }
        assertEquals(0, registry.size()); assertEquals(0, registry.partitions()); assertEquals(0, registry.sessions()); assertEquals(0, registry.notifications());
        assertNull(registry.maintenance()); assertNull(registry.notified());
    }
}
