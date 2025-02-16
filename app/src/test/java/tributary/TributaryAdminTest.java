// package tributary;

// import static org.junit.jupiter.api.Assertions.assertFalse;
// import static org.junit.jupiter.api.Assertions.assertNotNull;
// import static org.junit.jupiter.api.Assertions.assertNull;
// import static org.junit.jupiter.api.Assertions.assertTrue;
// import static
// com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;
// import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
// import static org.junit.jupiter.api.Assertions.assertEquals;

// import org.junit.jupiter.api.AfterEach;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;

// import io.github.cdimascio.dotenv.Dotenv;
// import tributary.core.tributaryObject.producers.Producer;
// import tributary.api.TributaryController;
// import tributary.core.tokenManager.TokenManager;
// import tributary.core.tributaryObject.ConsumerGroup;
// import tributary.core.tributaryObject.Topic;
// import tributary.core.tributaryObject.TributaryCluster;

// public class TributaryAdminTest {
// private TributaryController controller;
// private TributaryCluster cluster;
// private static final Dotenv dotenv = Dotenv.load();
// private final static String password = dotenv.get("SECRET_KEY");

// @BeforeEach
// public void setup() {
// controller = new TributaryController();
// cluster = TributaryCluster.getInstance();

// // Create Topics, Partitions, Producers, and Consumer Groups
// for (int i = 1; i <= 3; i++) {
// controller.createTopic("banana" + i, "string");
// controller.createPartition("banana" + i, "bananaFryingMethod" + i);
// controller.createPartition("banana" + i, "bananaBoilingMethod" + i);
// controller.createPartition("banana" + i, "bananaBakingMethod" + i);
// controller.createPartition("banana" + i, "bananaBlendingMethod" + i);
// }

// controller.createProducer("bananaBoiler", "banana1", "manual");
// controller.createProducer("bananaFrier", "banana2", "manual");
// controller.createProducer("bananaBaker", "banana3", "manual");
// controller.createProducer("bananaAdmin", "banana1", "manual");

// controller.createConsumerGroup("boiler", "banana1", "range");
// controller.createConsumerGroup("frier", "banana2", "range");
// controller.createConsumerGroup("baker", "banana3", "range");
// controller.createConsumerGroup("admin", "banana1", "range");

// for (ConsumerGroup<?> group : cluster.listConsumerGroups()) {
// for (int i = 1; i <= 4; i++) {
// controller.createConsumer(group.getId(), group.getId() + "Chef" + i);
// }
// }
// }

// @AfterEach
// public void tearDown() {
// TributaryCluster.setInstance(null);
// }

// @SuppressWarnings("unchecked")
// @Test
// public void testCreateAndChangeAdmins() {
// // Set initial admin for consumer group
// ConsumerGroup<String> groupAdmin = (ConsumerGroup<String>)
// cluster.getConsumerGroup("admin");
// controller.updateConsumerGroupAdmin("admin", null, null);
// assertTrue(TokenManager.validateToken(cluster.getAdminConsToken(), "admin",
// groupAdmin.getCreatedTime(),
// password));

// // Set initial admin for producer
// Producer<String> prodAdmin = (Producer<String>)
// cluster.getProducer("bananaAdmin");
// controller.updateProducerAdmin("bananaAdmin", null, null);
// assertTrue(TokenManager.validateToken(cluster.getAdminProdToken(),
// "bananaAdmin", prodAdmin.getCreatedTime(),
// password));

// // Change consumer group admin
// groupAdmin = (ConsumerGroup<String>) cluster.getConsumerGroup("boiler");
// controller.updateConsumerGroupAdmin("boiler", "admin", password);
// assertTrue(cluster.getAdminConsToken().equals(groupAdmin.getToken()));

// // Change producer admin
// prodAdmin = (Producer<String>) cluster.getProducer("bananaBoiler");
// controller.updateProducerAdmin("bananaBoiler", "bananaAdmin", password);
// assertTrue(cluster.getAdminProdToken().equals(prodAdmin.getToken()));
// }

// @Test
// public void testAdminAccessControlVerification() {
// // Setting up initial admin roles
// controller.updateConsumerGroupAdmin("admin", null, null);
// controller.updateProducerAdmin("bananaAdmin", null, null);

// ConsumerGroup<?> group = cluster.getConsumerGroup("admin");
// Producer<?> producer = cluster.getProducer("bananaAdmin");

// // Test valid token generation and validation for consumer group admin
// String validConsumerToken = TokenManager.generateToken(group.getId(),
// group.getCreatedTime());
// assertTrue(TokenManager.validateToken(validConsumerToken, "admin",
// group.getCreatedTime(), password));

// // Test invalid token for consumer group admin
// assertFalse(TokenManager.validateToken("invalidToken", "admin",
// group.getCreatedTime(), password));

// // Test valid token generation and validation for producer admin
// String validProducerToken = TokenManager.generateToken(producer.getId(),
// producer.getCreatedTime());
// assertTrue(TokenManager.validateToken(validProducerToken, "bananaAdmin",
// producer.getCreatedTime(), password));

// // Test invalid token for producer admin
// assertFalse(TokenManager.validateToken("invalidToken", "bananaAdmin",
// producer.getCreatedTime(), password));
// }

// @Test
// public void testUpdateAdminPermissions() throws Exception {
// // Set "admin" as initial consumer group admin
// controller.updateConsumerGroupAdmin("admin", null, null);
// assertTrue(cluster.getConsumerGroup("admin").listAssignedTopics().size() >
// 0);

// // Change admin to "boiler" with valid password
// controller.updateConsumerGroupAdmin("boiler", "admin", password);
// assertTrue(cluster.getConsumerGroup("boiler").listAssignedTopics().size() >
// 0);
// assertEquals(cluster.getAdminConsToken(),
// cluster.getConsumerGroup("boiler").getToken());

// // Try to set admin without required password (should fail)
// String output = tapSystemOut(() -> {
// assertDoesNotThrow(() -> controller.updateConsumerGroupAdmin("baker",
// "admin", null));
// });
// assertEquals("Incorrect token for old Consumer Group Admin.", output.trim());
// }

// @Test
// public void testRevokeAdminPermissionsOnChange() {
// // Set "admin" as the initial consumer group admin
// controller.updateConsumerGroupAdmin("admin", null, null);

// // Assign "boiler" as new admin and verify "admin" has no permissions
// controller.updateConsumerGroupAdmin("boiler", "admin", password);

// // Check that "admin" consumer group only has their originally assigned
// topics
// // and no token
// ConsumerGroup<?> oldAdminGroup = cluster.getConsumerGroup("admin");
// assertEquals(1, oldAdminGroup.listAssignedTopics().size());
// assertNull(oldAdminGroup.getToken());
// }

// @Test
// public void testRebalanceAfterAdminChange() {
// // Create initial admin and assign multiple partitions to ensure a non-empty
// // setup
// controller.updateConsumerGroupAdmin("admin", null, null);
// controller.updateRebalancing("admin", "roundrobin");

// // Change admin to "boiler" and trigger rebalancing
// controller.updateConsumerGroupAdmin("boiler", "admin", password);
// ConsumerGroup<?> newAdminGroup = cluster.getConsumerGroup("boiler");

// // Ensure that partitions were rebalanced correctly
// assertTrue(newAdminGroup.listConsumers().size() > 1);
// newAdminGroup.listConsumers().forEach(consumer ->
// assertTrue(consumer.listAssignedPartitions().size() > 0));
// }

// @Test
// public void testInvalidAdminAssignmentWithoutPermissions() throws Exception {
// // Attempt to assign a new admin without a previous admin - should fail
// String output = tapSystemOut(() -> {
// assertDoesNotThrow(() -> controller.updateConsumerGroupAdmin("admin",
// "boiler", password));
// });
// assertEquals("Old admin token not found.", output.trim());

// // Set "admin" as initial consumer group admin
// output = tapSystemOut(() -> {
// assertDoesNotThrow(() -> controller.updateConsumerGroupAdmin("admin", null,
// null));
// });
// ConsumerGroup<?> group = cluster.getConsumerGroup("admin");
// String showOutput = tapSystemOut(() -> {
// group.showTopics();
// group.showGroup();
// });
// assertEquals(showOutput, output);

// // Attempt to reassign admin role without providing password - should fail
// output = tapSystemOut(() -> {
// controller.updateConsumerGroupAdmin("baker", "admin", null);
// });
// ConsumerGroup<?> bakerGroup = cluster.getConsumerGroup("baker");
// String token = cluster.getAdminConsToken();
// assertFalse(TokenManager.validateToken(token, "baker",
// bakerGroup.getCreatedTime(), password));
// assertNotNull(token);
// assertNotNull(password);
// assertEquals("Incorrect token for old Consumer Group Admin.", output.trim());

// // Testing invalid admin update for producer without permissions
// output = tapSystemOut(() -> {
// controller.updateProducerAdmin("bananaBaker", "bananaAdmin", null);
// });
// assertEquals("Old admin token not found.", output.trim());

// // Non existent producer should not have an admin token and produce an error
// assertNotNull(cluster.getAdminConsToken());
// output = tapSystemOut(() -> {
// controller.updateConsumerGroupAdmin("baker", "fjdiosunovdjs", password);
// });
// assertEquals("Admin token exists but old Admin could not be identified.",
// output.trim());
// }

// @Test
// public void testAdminProductionAndConsumption() throws Exception {
// // Test producing an event without proper admin rights or permissions
// String output = tapSystemOut(() -> {
// controller.createEvent("bananaBaker", "banana1", "", "bananaFryingMethod1");
// });
// assertEquals("Producer does not have permission to produce to this topic.",
// output.trim());

// // Verify producing an event with admin token
// controller.updateProducerAdmin("bananaAdmin", null, null);
// output = tapSystemOut(() -> {
// assertDoesNotThrow(
// () -> controller.createEvent("bananaAdmin", "banana1", "blendBanana",
// "bananaBlendingMethod1"));
// });

// // Verify event was produced successfully
// int eventCount =
// cluster.getTopic("banana1").getPartition("bananaBlendingMethod1").listMessages().size();
// assertEquals("The event: blendBanana has been manually allocated to partition
// bananaBlendingMethod1",
// output.trim());
// assertEquals(eventCount, 1);

// // debugging
// for (Topic<?> topic : cluster.listTopics()) {
// topic.showTopic();
// }

// for (ConsumerGroup<?> group : cluster.listConsumerGroups()) {
// group.showGroup();
// }

// // Test consuming events without proper admin rights
// output = tapSystemOut(() -> {
// controller.consumeEvents("adminChef1", "bananaFryingMethod3", 7);
// });
// assertEquals("Consumer Group of Consumer does not have permission to consume
// from the topic.", output.trim());

// // Verify consuming events with admin token
// controller.updateConsumerGroupAdmin("admin", null, null);
// output = tapSystemOut(() -> {
// assertDoesNotThrow(() -> controller.consumeEvents("adminChef2",
// "bananaBlendingMethod1", 1));
// });
// String messageContent = "The event: blendBanana has been consumed by consumer
// adminChef2. It contains the contents:"
// + "\nduration = 2 mins\ndescription = Blend the banana with ice on high speed
// for 2 mins to make a smoothie.\n"
// + "speed = High\nConsumed 1 messages.";
// assertEquals(messageContent, output.trim());

// // Additional validation to confirm token is valid for the admin consumer and
// // producer
// ConsumerGroup<?> adminGroup = cluster.getConsumerGroup("admin");
// Producer<?> adminProducer = cluster.getProducer("bananaAdmin");

// assertNotNull(adminGroup.getToken());
// assertTrue(TokenManager.validateToken(adminGroup.getToken(), "admin",
// adminGroup.getCreatedTime(),
// password));
// assertNotNull(adminProducer.getToken());
// assertTrue(TokenManager.validateToken(adminProducer.getToken(),
// "bananaAdmin",
// adminProducer.getCreatedTime(), password));
// }

// }
