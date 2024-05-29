package tributary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import tributary.api.*;
import tributary.core.TributaryController;

public class TributaryTest {
    @Mock
    private TributaryCluster cluster;
    private TributaryController controller;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        cluster = TributaryCluster.getInstance();
        controller = mock(TributaryController.class);
    }

    @Test
    public void testCreateAndShowStringTopic() {
        controller.createTopic("banana", "string");
        Topic<?> topic = controller.getTopic("banana");
        assertNotNull(topic);
        assertEquals("banana", topic.getId());
    }

    @Test
    public void testCreatePartitionAndShow() {
        controller.createTopic("banana", "string");
        controller.createPartition("banana", "bananaCookingMethods");
        assertNotNull(controller.getTopic("banana").getPartition("bananaCookingMethods"));
    }

    @Test
    public void testDeleteConsumer() {
        controller.createTopic("banana", "string");
        controller.createConsumerGroup("bananaChefs", "banana", "range");
        controller.createConsumer("bananaChefs", "beginnerChef");
        controller.deleteConsumer("beginnerChef");
    }

    @Test
    public void testEventLifeCycle() {
        controller.createTopic("banana", "string");
        controller.createPartition("banana", "bananaCookingMethods");
        controller.createProducer("bananaBoiler", "string", "random");
        controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethods");
        controller.consumeEvents("beginnerChef", "bananaCookingMethods", 1);
        verify(controller).consumeEvents("beginnerChef", "bananaCookingMethods", 1);
    }

    @Test
    public void testCreateIntegerTopicAndProducers() {
        controller.createTopic("apple", "integer");
        controller.createProducer("bananaBoiler", "integer", "random");
        controller.createProducer("bananaFrier", "integer", "manual");
        assertNotNull(controller.getTopic("apple"));
        assertNotNull(controller.getProducer("bananaBoiler"));
        assertNotNull(controller.getProducer("bananaFrier"));
    }

    @Test
    public void testMessageCreationAndConsumption() {
        controller.createTopic("banana", "string");
        controller.createPartition("banana", "bananaCookingMethod1");
        controller.createProducer("bananaBoiler", "string", "random");
        controller.createEvent("bananaBoiler", "banana", "event1", "bananaCookingMethod1");
        controller.consumeEvents("beginnerChef1", "bananaCookingMethod1", 1);
    }

    @Test
    public void testRebalancingUpdate() {
        controller.createTopic("banana", "string");
        controller.createConsumerGroup("bananaChefs", "banana", "range");
        
        ConsumerGroup<?> group = controller.getConsumerGroup("bananaChefs");
        assertEquals("RangeStrategy", group.getRebalanceMethodName());
        controller.updateRebalancing("bananaChefs", "roundrobin");
        assertEquals("RoundRobinStrategy", group.getRebalanceMethodName());
    }
}
