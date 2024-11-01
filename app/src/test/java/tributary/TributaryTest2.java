package tributary;

import static org.junit.jupiter.api.Assertions.*;
import static com.github.stefanbirkner.systemlambda.SystemLambda.assertNothingWrittenToSystemErr;
import static com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tributary.api.Consumer;
import tributary.api.Partition;
import tributary.api.TributaryCluster;
import tributary.core.TributaryController;
import tributary.core.rebalancingStrategy.RangeStrategy;
import tributary.core.rebalancingStrategy.RoundRobinStrategy;

public class TributaryTest2 {

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

	@SuppressWarnings("unchecked")
	@Test
	public void mainComplexTest() throws Exception {
		// Topic and Partition creation - successful
		controller.createTopic("banana", "integer");
		controller.createPartition("banana", "bananaCookingMethod1");
		controller.createPartition("banana", "bananaCookingMethod2");
		controller.createPartition("banana", "bananaCookingMethod3");
		controller.createPartition("banana", "bananaCookingStyle4");
		assertEquals(1, cluster.listTopics().size());
		assertEquals(4, cluster.getTopic("banana").listPartitions().size());

		// Topic creation - errors
		String text = tapSystemOut(() -> {
			controller.createTopic("banana", "integer");
		});
		assertEquals("Topic banana already exists.", text.strip());
		assertThrows(IllegalArgumentException.class, () -> controller.createTopic("pingu", "GARBO!"));

		// Partition creation - errors
		text = tapSystemOut(() -> {
			controller.createPartition("banana", "bananaCookingMethod1");
		});
		assertEquals("Partition bananaCookingMethod1 already exists in topic.", text.strip());
		text = tapSystemOut(() -> {
			controller.createPartition("banana1", "bananaCookingMethod5");
		});
		assertEquals("Topic banana1 does not exist.", text.strip());

		// Producer creation
		controller.createProducer("bananaBoiler", "banana", "random");
		controller.createProducer("bananaFrier", "banana", "manual");
		assertEquals(2, cluster.listProducers().size());

		// Consumer Group creation
		controller.createConsumerGroup("bananaChefs", "banana", "range");
		assertEquals(0, cluster.getConsumerGroup("bananaChefs").listConsumers().size());
		assertSame(controller.getConsumerGroup("bananaChefs").getRebalanceMethodName(),
				RangeStrategy.class.getSimpleName());

		controller.createConsumerGroup("bananaChefs", "banana", "range");
		assertEquals(1, cluster.listConsumerGroups().size()); // Consumer Group already exists

		// Consumer creation
		controller.createConsumer("bananaChefs", "beginnerChef1");
		controller.createConsumer("bananaChefs", "beginnerChef2");
		controller.createConsumer("bananaChefs", "beginnerChef3");
		assertEquals(3, cluster.getConsumerGroup("bananaChefs").listConsumers().size());

		controller.updateRebalancing("bananaChefs", "roundrobin");
		assertSame(controller.getConsumerGroup("bananaChefs").getRebalanceMethodName(),
				RoundRobinStrategy.class.getSimpleName());

		// Test invalid event creation (wrong data type)
		assertThrows(IllegalArgumentException.class, () -> {
			controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethod1");
		});

		// Creating Events of correct type
		assertAll(
				"Valid Event Creation",
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaBoiler", "banana", "bananaBoilingNums", null)),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaBoiler", "banana", "bananaBoilingTemp", null)),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaBoiler", "banana", "bananaBoilingDur", null)),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaFrier", "banana", "bananaFryNums", "bananaCookingMethod2")),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaFrier", "banana", "bananaFryTemp", "bananaCookingMethod3")),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaFrier", "banana", "bananaFryDur", "bananaCookingMethod4")));

		// Invalid event type with different producers
		assertAll(
				"Invalid Event Creation",
				() -> assertThrows(IllegalArgumentException.class,
						() -> controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethod1")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> controller.createEvent("bananaBoiler", "banana", "fryBanana", "bananaCookingMethod2")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> controller.createEvent("bananaFrier", "banana", "boilBanana", "bananaCookingMethod1")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> controller.createEvent("bananaFrier", "banana", "fryBanana", "bananaCookingMethod3")));

		// Consumer offset setup and event consumption
		String partitionId = "bananaCookingMethod1";
		String consumerId = "beginnerChef1";
		Partition<Integer> partition = (Partition<Integer>) controller.findPartition(partitionId);
		Consumer<Integer> consumer = (Consumer<Integer>) controller.findConsumer(consumerId);

		// Adjust event count in partition if necessary
		while (partition.listMessages().size() < 3) {
			controller.createEvent("bananaFrier", "banana", "bananaFryNums", partitionId);
		}

		// Consume events until only one remains
		while (partition.listMessages().size() - partition.getOffset(consumer) > 1) {
			controller.consumeEvents(consumerId, partitionId, 1);
		}
		assertEquals(partition.listMessages().size(), partition.getOffset(consumer) + 1);

		// Consume the last event and confirm the final offset
		controller.consumeEvents(consumerId, partitionId, 1);
		assertEquals(partition.listMessages().size(), partition.getOffset(consumer));

		// Consume one more (should fail as partition is empty)
		controller.consumeEvents(consumerId, partitionId, 1);
		assertEquals(partition.listMessages().size(), partition.getOffset(consumer));

		// Test offset updates
		controller.updatePartitionOffset(consumerId, partitionId, 1);
		assertEquals(1, partition.getOffset(consumer));

		controller.updatePartitionOffset(consumerId, partitionId, 0);
		assertEquals(partition.listMessages().size(), partition.getOffset(consumer));
		assertNotNull(partition.getOffset(consumer));

		// Parallel produce test
		int previousSize = partition.listMessages().size();
		String[] produceArgs = { "bananaFrier", "banana", "bananaFryNums", partitionId, "bananaFrier", "banana",
				"bananaFryDur", partitionId };
		assertNothingWrittenToSystemErr(() -> controller.parallelProduce(produceArgs));
		assertEquals(previousSize + 2, partition.listMessages().size());

		// Parallel consume and final state verification
		String[] consumeArgs = { consumerId, partitionId, "beginnerChef2",
				partitionId, "5" };
		assertNothingWrittenToSystemErr(() -> controller.parallelConsume(consumeArgs));
		assertEquals(partition.listMessages().size() - 1, partition.getOffset(consumer));

		// Final assertions
		assertAll(
				"Final State Checks",
				() -> assertEquals(partition.listMessages().size() - 1, partition.getOffset(consumer)),
				() -> assertDoesNotThrow(() -> controller.consumeEvents("beginnerChef1", partitionId, 1)),
				() -> assertEquals(partition.listMessages().size(), partition.getOffset(consumer)));
	}
}
