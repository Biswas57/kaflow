package tributary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import tributary.api.TributaryController;
import tributary.core.tributaryObject.Consumer;
import tributary.core.tributaryObject.ConsumerGroup;
import tributary.core.tributaryObject.Partition;
import tributary.core.tributaryObject.Topic;

import java.time.LocalDateTime;
import java.util.UUID;

public class OffsetManagementTest {
    private TributaryController controller;
    private String testTopicName;

    @BeforeEach
    public void setup() {
        controller = new TributaryController();
        testTopicName = "testTopic_" + UUID.randomUUID().toString().replace("-", "");

        // Create a topic and partition
        controller.createTopic(testTopicName, "string");
        controller.createPartition(testTopicName, "partition1");

        // Create producer and produce some messages
        controller.createProducer("testProducer_" + testTopicName, testTopicName, "manual");

        // Produce messages to have data to consume
        for (int i = 0; i < 5; i++) {
            controller.produceMessage("testProducer_" + testTopicName, testTopicName, "string",
                    ("key" + i).getBytes(), "message" + i, LocalDateTime.now(), "partition1");
        }

        // Create consumer group with one consumer
        controller.createConsumerGroup("testGroup_" + testTopicName, testTopicName, "range");
        controller.createConsumer("testGroup_" + testTopicName, "consumer1");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testOffsetPreservedDuringRebalancing() {
        String groupName = "testGroup_" + testTopicName;

        // Get references to our objects
        Topic<String> topic = (Topic<String>) controller.showTopic(testTopicName);
        ConsumerGroup<String> group = (ConsumerGroup<String>) controller.showGroup(groupName);
        Partition<String> partition = topic.getPartition("partition1");
        Consumer<String> consumer1 = group.getConsumer("consumer1");

        // Initial offset should be 0
        assertEquals(0, partition.getOffset(groupName));

        // Consume 2 messages
        controller.getHelper().consumeEventsGeneric(consumer1, partition);
        controller.getHelper().consumeEventsGeneric(consumer1, partition);

        // Offset should now be 2
        assertEquals(2, partition.getOffset(groupName));

        // Add a second consumer to trigger rebalancing
        controller.createConsumer(groupName, "consumer2");

        // After rebalancing, the offset should still be 2 (group progress preserved)
        assertEquals(2, partition.getOffset(groupName));

        // Both consumers should be able to continue from where the group left off
        // Try to consume with either consumer - should get the next message (index 2)
        String result = (String) controller.getHelper().consumeEventsGeneric(consumer1, partition);
        assertEquals("message2", result);

        // Offset should now be 3
        assertEquals(3, partition.getOffset(groupName));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNewGroupStartsFromZero() {
        String originalGroupName = "testGroup_" + testTopicName;
        String newGroupName = "testGroup2_" + testTopicName;

        // Create a second consumer group for the same topic
        controller.createConsumerGroup(newGroupName, testTopicName, "range");
        controller.createConsumer(newGroupName, "consumer3");

        Topic<String> topic = (Topic<String>) controller.showTopic(testTopicName);
        Partition<String> partition = topic.getPartition("partition1");
        ConsumerGroup<String> group2 = (ConsumerGroup<String>) controller.showGroup(newGroupName);
        Consumer<String> consumer3 = group2.getConsumer("consumer3");

        // New group should start from offset 0
        assertEquals(0, partition.getOffset(newGroupName));

        // Consume a message
        String result = (String) controller.getHelper().consumeEventsGeneric(consumer3, partition);
        assertEquals("message0", result);

        // Offset should now be 1 for the new group
        assertEquals(1, partition.getOffset(newGroupName));

        // Original group's offset should be unaffected
        assertEquals(0, partition.getOffset(originalGroupName));
    }
}
