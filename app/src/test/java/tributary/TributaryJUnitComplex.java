package tributary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

public class TributaryJUnitComplex {

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

	/*
	 * This is a test I have made for testing the following in the Tributary
	 * Structure:
	 * - Creating an Integer type Topic
	 * - Creating multiple Partitions, Consumers and Events in the Tributary Cluster
	 * - Creating Producers with Random AND Manual allocation.
	 * - Creating Consumer Groups with Range and RoundRobin rebalancing strategies
	 * - Consuming Events in a Partition to show full consumer functionality
	 * - Updating Consumer Offsets to show Message Playback and error handling
	 * - Parallel Message Production and Consumption to show Thread Safety
	 */
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
		assertEquals("Partition bananaCookingMethod1 already exists.", text.strip());
		text = tapSystemOut(() -> {
			controller.createPartition("banana1", "bananaCookingMethod5");
		});
		assertEquals("Topic banana1 does not exist.", text.strip());

		// Producer creation
		controller.createProducer("bananaBoiler", "integer", "random");
		controller.createProducer("bananaFrier", "integer", "manual");
		assertEquals(cluster.listProducers().size(), 2);

		// Consumer Group creation
		controller.createConsumerGroup("bananaChefs", "banana", "range");
		assertEquals(cluster.getConsumerGroup("bananaChefs").listConsumers().size(), 0);
		assertSame(controller.getConsumerGroup("bananaChefs").getRebalanceMethodName(),
				RangeStrategy.class.getSimpleName());

		controller.createConsumerGroup("bananaChefs", "banana", "range");
		// Consumer Group already exists
		assertEquals(cluster.listConsumerGroups().size(), 1);

		// Consumer creation
		controller.createConsumer("bananaChefs", "beginnerChef1");
		controller.createConsumer("bananaChefs", "beginnerChef2");
		controller.createConsumer("bananaChefs", "beginnerChef3");
		assertEquals(cluster.getConsumerGroup("bananaChefs").listConsumers().size(), 3);
		assertSame(controller.getConsumerGroup("bananaChefs").getConsumer("beginnerChef1")
				.listAssignedPartitions()
				.get(1).getId(), "bananaCookingMethod2");

		controller.updateRebalancing("bananaChefs", "roundrobin");
		assertSame(controller.getConsumerGroup("bananaChefs").getRebalanceMethodName(),
				RoundRobinStrategy.class.getSimpleName());
		assertSame(controller.getConsumerGroup("bananaChefs").getConsumer("beginnerChef1")
				.listAssignedPartitions()
				.get(1).getId(), "bananaCookingStyle4");

		// string type message should not be able to be added to an integer type topic,
		// Partition or message
		assertThrows(IllegalArgumentException.class, () -> {
			controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethod1");
		});

		// Creating Events of correct type that should not throw an exception
		assertAll(
				"Creating Events that should not throw an exception",
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaBoiler", "banana",
								"bananaBoilingNums", null)),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaBoiler", "banana",
								"bananaBoilingTemp", null)),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaBoiler", "banana",
								"bananaBoilingDur", null)),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaFrier", "banana", "bananaFryNums",
								"bananaCookingMethod2")),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaFrier", "banana", "bananaFryTemp",
								"bananaCookingMethod3")),
				() -> assertDoesNotThrow(
						() -> controller.createEvent("bananaFrier", "banana", "bananaFryDur",
								"bananaCookingMethod4")));

		// Creating Events of incorrect type (String) with different type Producers
		// (Integer) should cause code to throw an exception
		assertAll(
				"Creating Events that should throw an exception",
				() -> assertThrows(IllegalArgumentException.class,
						() -> controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethod1")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> controller.createEvent("bananaBoiler", "banana", "fryBanana", "bananaCookingMethod2")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> controller.createEvent("bananaFrier", "banana", "boilBanana",
								"bananaCookingMethod1")),
				() -> assertThrows(IllegalArgumentException.class, () -> controller.createEvent("bananaFrier", "banana",
						"fryBanana", "bananaCookingMethod3")));

		// Event Consumption - Setting up partition and consumer
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
					() -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana",
							"bananaFryNums",
							"bananaCookingMethod1")),
					() -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana",
							"bananaFryTemp",
							"bananaCookingMethod1")),
					() -> assertDoesNotThrow(() -> controller.createEvent("bananaFrier", "banana",
							"bananaFryDur",
							"bananaCookingMethod1")));
		}

		Partition<Integer> partition = (Partition<Integer>) controller.findPartition(partitionId);
		Consumer<Integer> consumer = (Consumer<Integer>) controller.findConsumer(consumerId);

		// consume Events in partition bananaCookingMethod1 until there are is 1 message
		// left
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

		// Playback with 2 of the Partitions for Consumer 1
		controller.updateConsumerOffset(consumerId, partitionId, 1);
		int offset = consumer.getOffset(partition);
		assertEquals(1, offset);
		controller.updateConsumerOffset(consumerId, partitionId, 0);
		offset = consumer.getOffset(partition);
		assertEquals(partition.listMessages().size(), offset);
		int previousSize = partition.listMessages().size();

		// parrallel produce 2 new messages for the partition
		String[] args = { "bananaFrier", "banana", "bananaFryNums", partitionId, "bananaFrier", "banana",
				"bananaFryDur", partitionId };
		assertNothingWrittenToSystemErr(() -> controller.parallelProduce(args));
		assertEquals(partition.listMessages().size(), previousSize + 2);

		// get list size of one of consumers, replay from beginning. Add another
		// consumer and parallel consume until end of partition both consumers
		// partitions
		String[] consumeArgs = { consumerId, partitionId, "beginnerChef2", partitionId, "5" };
		assertNothingWrittenToSystemErr(() -> controller.parallelConsume(consumeArgs));
		assertEquals(consumer.getOffset(partition), partition.listMessages().size() - 1);

		// Checking final state and ensuring everything is consumed correctly
		final String finalPartition = partition.getId();
		assertAll(
				"Checking final state",
				() -> assertEquals(consumer.getOffset(partition), partition.listMessages().size() - 1),
				() -> assertDoesNotThrow(() -> controller.consumeEvents("beginnerChef1", finalPartition, 1)),
				() -> assertEquals(consumer.getOffset(partition), partition.listMessages().size() - 1));
	}
}
