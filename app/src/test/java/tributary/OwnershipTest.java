package tributary;

import org.junit.jupiter.api.Test;
import tributary.api.TributaryController;
import tributary.cli.MessageHandler;
import tributary.core.tributaryFactory.FactoryRegistry;
import tributary.core.tributaryObject.TributaryCluster;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipTest {
    @Test
    void controllersAndHelpersKeepIdenticalIdentifiersInIndependentClusters() {
        TributaryCluster left = new TributaryCluster();
        TributaryCluster right = new TributaryCluster();
        TributaryController first = new TributaryController(left);
        TributaryController second = new TributaryController(right);
        for (TributaryController controller : new TributaryController[]{first, second}) {
            controller.createTopic("orders", "string");
            controller.createPartition("orders", "p0");
            controller.createProducer("producer", "orders", "manual");
            controller.createConsumerGroup("group", "orders", "range");
            controller.createConsumer("group", "consumer");
        }
        first.produceMessage("producer", "orders", "string", new byte[0], "only-left",
                LocalDateTime.of(2026, 1, 1, 0, 0), "p0");
        assertEquals(1, left.getTopic("orders").getPartition("p0").listMessages().size());
        assertEquals(0, right.getTopic("orders").getPartition("p0").listMessages().size());
        assertEquals("only-left", first.consumeEvent("consumer", "p0"));
        assertEquals(1, left.getTopic("orders").getPartition("p0").getOffset("group"));
        assertEquals(0, right.getTopic("orders").getPartition("p0").getOffset("group"));
        assertNotSame(first.showTopic("orders"), second.showTopic("orders"));
        assertNotSame(first.showGroup("group"), second.showGroup("group"));
        assertNotSame(left.getProducer("producer"), right.getProducer("producer"));
        first.deleteTopic("orders");
        assertNotNull(second.showTopic("orders"));
    }

    @Test
    void factoriesCacheOnlyWithinTheirInjectedCluster() {
        TributaryCluster first = new TributaryCluster();
        TributaryCluster second = new TributaryCluster();
        FactoryRegistry left = new FactoryRegistry(first);
        FactoryRegistry right = new FactoryRegistry(second);
        assertSame(left.get(String.class), left.get(String.class));
        assertNotSame(left.get(String.class), right.get(String.class));
        left.get(String.class).createTopic("same");
        right.get(String.class).createTopic("same");
        left.get(String.class).createPartition("same", "p0");
        assertNotNull(first.getTopic("same").getPartition("p0"));
        assertNull(second.getTopic("same").getPartition("p0"));
    }

    @Test
    void cliAdapterUsesItsSuppliedController() {
        TributaryController first = new TributaryController(new TributaryCluster());
        TributaryController second = new TributaryController(new TributaryCluster());
        MessageHandler handler = new MessageHandler(first);
        handler.handleCreateCommand(new String[]{"create", "topic", "cli-orders", "string"});
        assertNotNull(first.showTopic("cli-orders"));
        assertThrows(IllegalArgumentException.class, () -> second.showTopic("cli-orders"));
    }
}
