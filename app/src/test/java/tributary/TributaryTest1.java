package tributary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tributary.api.TributaryController;
import tributary.api.TributaryHelper;
import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.Partition;
import tributary.core.tributaryObject.TributaryCluster;

public class TributaryTest1 {
    private TributaryController controller;
    private TributaryCluster cluster;
    private TributaryHelper helper = new TributaryHelper();

    @BeforeEach
    public void setup() {
        controller = new TributaryController();
        helper = new TributaryHelper();
        cluster = TributaryCluster.getInstance();
    }

    @AfterEach
    public void tearDown() {
        TributaryCluster.setInstance(null);
    }

    /*
     * This is a test I have made for testing the creation of 1 of
     * everything in the Tributary Structure
     */
    @SuppressWarnings("unchecked")
    @Test
    public void mainSimpleTest() {
        controller.createTopic("banana", "string");
        assertEquals(1, cluster.listTopics().size());

        controller.createPartition("banana", "bananaCookingMethods");
        controller.createPartition("banana", "bananaCookingStyles");
        assertEquals(cluster.getTopic("banana").listPartitions().size(), 2);

        controller.createProducer("bananaBoiler", "banana", "manual");
        assertEquals(cluster.getProducer("bananaBoiler").getId(), "bananaBoiler");

        controller.createConsumerGroup("bananaChefs", "banana", "range");
        assertEquals(cluster.listConsumerGroups().size(), 1);

        controller.createConsumer("bananaChefs", "beginnerChef");
        controller.createConsumer("bananaChefs", "deleteBeginnerChef");
        assertEquals(cluster.getConsumerGroup("bananaChefs").listConsumers().size(), 2);
        assertThrows(IOException.class, () -> {
            controller.createEvent("bananaBoiler", "banana", "noBanana", "bananaCookingMethods");
        });

        assertDoesNotThrow(() -> {
            controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethods");
        });
        assertEquals(1, cluster.getTopic("banana").getPartition("bananaCookingMethods").listMessages().size());

        controller.createProducer("bananaFrier", "banana", "manual");
        assertDoesNotThrow(() -> {
            controller.createEvent("bananaFrier", "banana", "fryBanana", "bananaCookingStyles");
        });
        assertEquals(1, cluster.getTopic("banana").getPartition("bananaCookingStyles").listMessages().size());

        assertAll(
                "Partition assignment should be equal before Consumer deletion",
                () -> assertEquals(cluster.getConsumerGroup("bananaChefs")
                        .getConsumer("deleteBeginnerChef").listAssignedPartitions().size(), 1),
                () -> assertEquals(1, helper.findConsumer("beginnerChef").listAssignedPartitions().size()));

        // After deleting the consumer, the partition should be reassigned to the other
        // consumer (ie. rebalanced using the Observer Patter)
        controller.deleteConsumer("deleteBeginnerChef");
        assertEquals(
                cluster.getConsumerGroup("bananaChefs").getConsumer("beginnerChef").listAssignedPartitions().size(),
                2);

        controller.showTopic("banana");
        Partition<String> partition = (Partition<String>) helper.findPartition("bananaCookingMethods");
        Consumer<String> consumer = (Consumer<String>) helper.findConsumer("beginnerChef");

        // Partition offset starts at -1 because list of Messages in Partition are 0
        // indexed
        assertEquals(0, partition.getOffset(consumer));

        controller.consumeEvents("beginnerChef", "bananaCookingMethods", 1);
        assertEquals(1, partition.getOffset(consumer));

        // offset stays at 0 because there is only 1 message in the partition
        controller.consumeEvents("beginnerChef", "bananaCookingMethods", 1);
        assertEquals(partition.getOffset(consumer), 1);
    }
}
