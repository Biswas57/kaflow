package tributary;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import tributary.api.TributaryController;

public class SimpleOffsetTest {

    @Test
    public void testBasicInstantiation() {
        TributaryController controller = new TributaryController();
        assertNotNull(controller);
    }

    @Test
    public void testCreateTopic() {
        TributaryController controller = new TributaryController();

        try {
            controller.createTopic("testTopic", "string");
            // If we get here, the topic was created successfully
            assertTrue(true);
        } catch (Exception e) {
            System.out.println("Error creating topic: " + e.getMessage());
            e.printStackTrace();
            fail("Failed to create topic: " + e.getMessage());
        }
    }
}
