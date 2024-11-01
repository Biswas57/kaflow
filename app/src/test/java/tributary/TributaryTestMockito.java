package tributary;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import tributary.api.*;
import tributary.core.TributaryController;

public class TributaryTestMockito {
    @Mock
    private TributaryCluster cluster;
    private TributaryController controller;
    private AutoCloseable mocks;

    @BeforeEach
    public void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        controller = spy(new TributaryController());

        controller.createTopic("banana", "string");
        controller.createPartition("banana", "bananaCookingMethods");
        controller.createProducer("bananaBoiler", "banana", "manual");
        controller.createConsumerGroup("bananaChefs", "banana", "range");
        controller.createConsumer("bananaChefs", "beginnerChef");
    }

    @AfterEach
    public void tearDown() throws Exception {
        reset(controller, cluster);
        mocks.close();
    }

    @Test
    public void testCreateAndShowStringTopic() {
        verify(controller).createTopic("banana", "string");
    }

    @Test
    public void testCreatePartition() {
        verify(controller).createPartition("banana", "bananaCookingMethods");
    }

    @Test
    public void testDeleteConsumer() {
        verify(controller).createConsumer("bananaChefs", "beginnerChef");

        controller.deleteConsumer("beginnerChef");
        verify(controller).deleteConsumer("beginnerChef");
    }

    @Test
    public void testEventLifeCycle() {
        assertDoesNotThrow(() -> {
            controller.createEvent("bananaBoiler", "banana", "boilBanana",
                    "bananaCookingMethods");
        });
        controller.consumeEvents("beginnerChef", "bananaCookingMethods", 1);

        assertDoesNotThrow(() -> {
            controller.createEvent("bananaBoiler", "banana", "fryBanana",
                    "bananaCookingMethods");
        });
        controller.consumeEvents("beginnerChef", "bananaCookingMethods", 1);
        verify(controller, times(2)).consumeEvents("beginnerChef",
                "bananaCookingMethods", 1);
    }

    @Test
    public void testCreateIntegerTopicAndProducers() {
        controller.createTopic("apple", "integer");
        verify(controller).createTopic("apple", "integer");

        controller.createProducer("bananaBoiler", "banana", "random");
        // with capital I for Integer
        controller.createProducer("bananaFrier", "banana", "manual");

        // has 3 invocations because of the BeforeEach setup
        verify(controller, times(3)).createProducer(anyString(), anyString(),
                anyString());
    }

    @Test
    public void testMessageCreationAndConsumption() {
        controller.createTopic("banana", "integer");
        controller.createPartition("banana", "bananaCookingMethod1");
        controller.createProducer("bananaBoiler", "banana", "random");
        controller.createConsumerGroup("bananaChefs", "banana", "range");
        controller.createConsumer("bananaChefs", "beginnerChef1");

        assertDoesNotThrow(() -> {
            controller.createEvent("bananaBoiler", "banana", "fryBanana",
                    "bananaCookingMethods");
        });
        controller.consumeEvents("beginnerChef1", "bananaCookingMethod1", 1);
        verify(controller, times(1)).consumeEvents("beginnerChef1",
                "bananaCookingMethod1", 1);
    }

    @Test
    public void testUpdateConsumerOffset() {
        controller.updatePartitionOffset("beginnerChef", "bananaCookingMethods", 5);
        verify(controller).updatePartitionOffset("beginnerChef",
                "bananaCookingMethods", 5);
    }

    @Test
    public void testParallelProduceTwoMessages() {
        String[] produceParts = { "bananaBoiler", "banana", "boilBanana",
                "bananaCookingMethods",
                "bananaBoiler", "banana", "fryBanana", "bananaCookingMethods" };

        controller.parallelProduce(produceParts);
        assertDoesNotThrow(() -> {
            verify(controller, times(2)).createEvent(anyString(), anyString(),
                    anyString(), anyString());
        });
    }

    @Test
    public void testParallelProduceThreeMessages() {
        String[] produceParts = { "bananaBoiler", "banana", "boilBanana",
                "bananaCookingMethods",
                "bananaBoiler", "banana", "fryBanana", "bananaCookingMethods",
                "bananaBoiler", "banana", "grillBanana", "bananaCookingMethods" };

        controller.parallelProduce(produceParts);
        assertDoesNotThrow(() -> {
            verify(controller, times(3)).createEvent(eq("bananaBoiler"), eq("banana"),
                    any(String.class),
                    eq("bananaCookingMethods"));
        });
    }

    @Test
    public void testParallelConsumeTwoMessages() {
        String[] produceParts = { "bananaBoiler", "banana", "boilBanana",
                "bananaCookingMethods",
                "bananaBoiler", "banana", "fryBanana", "bananaCookingMethods",
                "bananaBoiler", "banana", "grillBanana", "bananaCookingMethods" };

        controller.parallelProduce(produceParts);
        assertDoesNotThrow(() -> {
            verify(controller, times(3)).createEvent(anyString(), anyString(),
                    anyString(), anyString());
        });

        String[] consumeParts = { "beginnerChef", "bananaCookingMethods", "2" };
        controller.parallelConsume(consumeParts);
        assertDoesNotThrow(() -> {
            verify(controller, times(1)).consumeEvents(eq("beginnerChef"),
                    eq("bananaCookingMethods"), eq(2));
        });
    }

    @Test
    public void testParallelConsumeThreeMessages() {
        String[] produceParts = { "bananaBoiler", "banana", "boilBanana",
                "bananaCookingMethods",
                "bananaBoiler", "banana", "fryBanana", "bananaCookingMethods",
                "bananaBoiler", "banana", "grillBanana", "bananaCookingMethods" };

        controller.parallelProduce(produceParts);
        assertDoesNotThrow(() -> {
            verify(controller, times(3)).createEvent(anyString(), anyString(),
                    anyString(), anyString());
        });

        String[] consumeParts = { "beginnerChef", "bananaCookingMethods", "3" };
        controller.parallelConsume(consumeParts);
        assertDoesNotThrow(() -> {
            verify(controller, times(1)).consumeEvents(eq("beginnerChef"),
                    eq("bananaCookingMethods"), eq(3));
        });
    }
}
