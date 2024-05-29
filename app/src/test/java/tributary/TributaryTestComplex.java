package tributary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tributary.api.Consumer;
import tributary.api.Partition;
import tributary.api.TributaryCluster;
import tributary.core.TributaryController;
import tributary.core.rebalancingStrategy.RangeStrategy;
import tributary.core.rebalancingStrategy.RoundRobinStrategy;

public class TributaryTestComplex {
    private TributaryController controller;
    private TributaryCluster cluster;

    @BeforeEach
    public void setup() {
        controller = new TributaryController();
        cluster = TributaryCluster.getInstance();
    }

    @AfterEach
    public void tearDown() {
        TributaryCluster.setInstance(null);
    }

    /* This is a test I have made for testing the following in the Tributary Structure:
     * - Creating an Integer type Topic
     * - Creating multiple Partitions, Consumers and Events in the Tributary Cluster
     * - Creating Producers with Random AND Manual allocation.
     * - Creating Consumer Groups with Range and RoundRobin rebalancing strategies
     * - Consuming Events in a Partition to show full consumer functionality
    */
    @SuppressWarnings("unchecked")
    @Test
    public void mainComplexTest() {
        controller.createTopic("banana", "integer");
        controller.createPartition("banana", "bananaCookingMethod1");
        controller.createPartition("banana", "bananaCookingMethod2");
        controller.createPartition("banana", "bananaCookingMethod3");
        controller.createPartition("banana", "bananaCookingStyle4");
        assertEquals(cluster.getTopic("banana").listPartitions().size(), 4);

        controller.createProducer("bananaBoiler", "integer", "random");
        controller.createProducer("bananaFrier", "integer", "manual");
        assertEquals(cluster.listProducers().size(), 2);

        controller.createConsumerGroup("bananaChefs", "banana", "range");
        assertEquals(cluster.getConsumerGroup("bananaChefs").listConsumers().size(), 0);
        assertSame(controller.getConsumerGroup("bananaChefs").getRebalanceMethodName(), RangeStrategy.class.getSimpleName());
        
        controller.createConsumerGroup("bananaChefs", "banana", "range");
        // Consumer Group already exists
        assertEquals(cluster.listConsumerGroups().size(), 1);

        controller.createConsumer("bananaChefs", "beginnerChef1");
        controller.createConsumer("bananaChefs", "beginnerChef2");
        controller.createConsumer("bananaChefs", "beginnerChef3");
        assertEquals(cluster.getConsumerGroup("bananaChefs").listConsumers().size(), 3);
        assertSame(controller.getConsumerGroup("bananaChefs").getConsumer("beginnerChef1").listAssignedPartitions().get(1).getId(), "bananaCookingMethod2");

        controller.updateRebalancing("bananaChefs", "roundrobin");
        assertSame(controller.getConsumerGroup("bananaChefs").getRebalanceMethodName(), RoundRobinStrategy.class.getSimpleName());
        assertSame(controller.getConsumerGroup("bananaChefs").getConsumer("beginnerChef1").listAssignedPartitions().get(1).getId(), "bananaCookingStyle4");

        // string type message should not be able to be added to an integer type topic, Partition or message
        assertThrows(ClassCastException.class, () -> {
            controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethod1");
        });

        assertAll(
            "Creating Events that should not throw an exception",
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaBoiler", "banana", "bananaBoilingNums", null)),
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaBoiler", "banana", "bananaBoilingTemp", null)),
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaBoiler", "banana", "bananaBoilingDur", null)),
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana", "bananaFryNums", "bananaCookingMethod2")),
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana", "bananaFryTemp", "bananaCookingMethod3")),
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana", "bananaFryDur", "bananaCookingMethod4"))
        );

        String partitionId = "bananaCookingMethod1";
        String consumerId = "beginnerChef1";
        if (controller.findPartition("bananaCookingMethod1").listMessages().size() >= 3) {
            partitionId = "bananaCookingMethod1";
            consumerId = "beginnerChef1";
        } else if (controller.findPartition("bananaCookingMethod2").listMessages().size() >= 3) {
            partitionId = "bananaCookingMethod2";
            consumerId = "beginnerChef2";
        } else if (controller.findPartition("bananaCookingMethod3").listMessages().size() >= 3) {
            partitionId = "bananaCookingMethod3";
            consumerId = "beginnerChef3";
        } else if (controller.findPartition("bananaCookingStyle4").listMessages().size() >= 3) {
            partitionId = "bananaCookingStyle4";
            consumerId = "beginnerChef1";
        } else {
            // if no partition has 3 messages, create 3 more messages in the first partition
            assertAll(
            "Creating Events to show consumer can consume events with offsetting",
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana", "bananaFryNums", "bananaCookingMethod1")),
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana", "bananaFryTemp", "bananaCookingMethod1")),
            () -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana", "bananaFryDur", "bananaCookingMethod1"))
        );
        }

        Partition<Integer> partition = (Partition<Integer>) controller.findPartition(partitionId);
        Consumer<Integer> consumer = (Consumer<Integer>) controller.findConsumer(consumerId);

        // consume Events in partition bananaCookingMethod1 until there are is 1 message left
        while (partition.listMessages().size() - consumer.getOffset(partition) > 2) {
            controller.consumeEvents(consumerId, partitionId, 1);
        }

        // right before consuming the last message in the partition
        // offset is +1 because the offset starts at -1
        assertEquals(partition.listMessages().size() - 1, consumer.getOffset(partition) + 1);

        // consume the last message in the partition
        controller.consumeEvents(consumerId, partitionId, 1);
        assertEquals(partition.listMessages().size(), consumer.getOffset(partition) + 1);

        // consume 1 more message in the partition (should not be able to)
        controller.consumeEvents(consumerId, partitionId, 1);
        assertEquals(partition.listMessages().size(), consumer.getOffset(partition) + 1);
    }
}
