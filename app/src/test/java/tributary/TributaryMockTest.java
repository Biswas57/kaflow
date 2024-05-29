package tributary;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import tributary.api.*;
import tributary.core.TributaryController;

public class TributaryMockTest {
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
        verify(controller).createTopic("banana", "string");
    }

    @Test
    public void testCreatePartitionAndShow() {
        controller.createTopic("banana", "string");
        controller.createPartition("banana", "bananaCookingMethods");
        verify(controller).createPartition("banana", "bananaCookingMethods");
    }

    @Test
    public void testDeleteConsumer() {
        controller.createTopic("banana", "string");
        controller.createConsumerGroup("bananaChefs", "banana", "range");

        controller.createConsumer("bananaChefs", "beginnerChef");
        verify(controller).createConsumer("bananaChefs", "beginnerChef");

        controller.deleteConsumer("beginnerChef");
        verify(controller).deleteConsumer("beginnerChef");
    }

    @Test
    public void testEventLifeCycle() {
        controller.createTopic("banana", "string");
        controller.createPartition("banana", "bananaCookingMethods");
        controller.createProducer("bananaBoiler", "string", "manual");
        controller.createConsumerGroup("bananaChefs", "banana", "range");
        controller.createConsumer("bananaChefs", "bananaBoiler");

        assertDoesNotThrow(() -> {
            controller.createEvent("bananaBoiler", "banana", "boilBanana", "bananaCookingMethods");
        });
        controller.consumeEvents("beginnerChef", "bananaCookingMethods", 1);

        assertDoesNotThrow(() -> {
            controller.createEvent("bananaBoiler", "banana", "fryBanana", "bananaCookingMethods");
        });
        controller.consumeEvents("beginnerChef", "bananaCookingMethods", 1);
        verify(controller, times(2)).consumeEvents("beginnerChef", "bananaCookingMethods", 1);

    }

    @Test
    public void testCreateIntegerTopicAndProducers() {
        controller.createTopic("apple", "integer");
        verify(controller).createTopic("apple", "integer");

        controller.createProducer("bananaBoiler", "integer", "random");
        controller.createProducer("bananaFrier", "integer", "manual");
        verify(controller, times(2)).createProducer(anyString(), anyString(), anyString());
    }

    @Test
    public void testMessageCreationAndConsumption() {
        controller.createTopic("banana", "integer");
        controller.createPartition("banana", "bananaCookingMethod1");
        controller.createProducer("bananaBoiler", "integer", "random");
        controller.createConsumerGroup("bananaChefs", "banana", "range");
        controller.createConsumer("bananaChefs", "beginnerChef1");

        assertDoesNotThrow(() -> {
            controller.createEvent("bananaBoiler", "banana", "bananaFryDur", "bananaCookingMethods");
        });
        controller.consumeEvents("beginnerChef1", "bananaCookingMethod1", 1);
        verify(controller, times(1)).consumeEvents("beginnerChef1", "bananaCookingMethod1", 1);
    }
}
