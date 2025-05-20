package tributary.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import org.json.JSONArray;
import org.json.JSONObject;

import tributary.core.tributaryObject.producers.*;
import tributary.core.parameterDataStructures.ParallelEventRequest;
import tributary.core.rebalancingStrategy.RebalancingStrategy;
import tributary.core.tokenManager.TokenManager;
import tributary.core.tributaryFactory.*;
import tributary.core.tributaryObject.*;

/**
 * This class represents the main controller for managing and interacting with
 * the components of the Tributary Cluster. It provides functionality to create,
 * update, and manage topics, partitions, producers, consumers, and consumer
 * groups, as well as supporting parallel operations and event consumption.
 */
public class TributaryController {
    private TributaryCluster cluster;
    private TributaryHelper helper;

    private static final Map<String, Class<?>> keyToClass = Map.of(
            "integer", Integer.class,
            "string", String.class,
            "bytes", byte[].class);

    /**
     * Constructor to initialize the TributaryController with default settings.
     * It sets up the cluster, object factory, and type mappings.
     */
    public TributaryController() {
        this.cluster = TributaryCluster.getInstance();
        this.helper = new TributaryHelper();
    }

    public TributaryHelper getHelper() {
        return helper;
    }

    private static Class<?> castKeyType(String key) {
        Class<?> c = keyToClass.get(key.toLowerCase());
        if (c == null)
            throw new IllegalArgumentException("Unsupported type: " + key);
        return c;
    }

    private static <T> ObjectFactory<T> getFactory(Class<T> t) {
        return FactoryRegistry.get(t);
    }

    /*
     * Creation methods for objects in the Tributary Cluster.
     */

    /**
     * Creates a new topic in the Tributary Cluster.
     *
     * @param topicId The identifier for the new topic.
     * @param type    The type of events that the topic will handle.
     * @throws IllegalArgumentException if the topic already exists or the type is
     *                                  unsupported.
     */
    public void createTopic(String topicId, String type) throws IllegalArgumentException {
        if (helper.getTopic(topicId) != null) {
            throw new IllegalArgumentException("Topic " + topicId + " already exists.");
        }
        getFactory(castKeyType(type)).createTopic(topicId);
    }

    /**
     * Creates a new partition in the specified topic.
     *
     * @param topicId     The identifier of the topic to add the partition to.
     * @param partitionId The identifier for the new partition.
     * @throws IllegalArgumentException if the topic does not exist or the partition
     *                                  already exists.
     */
    public void createPartition(String topicId, String partitionId) throws IllegalArgumentException {
        Topic<?> topic = helper.getTopic(topicId);
        if (topic == null) {
            throw new IllegalArgumentException("Topic " + topicId + " does not exist.");
        } else if (topic.containsPartition(partitionId)) {
            throw new IllegalArgumentException(
                    "Partition " + partitionId + " already exists in topic " + topicId + ".");
        }
        String topicType = helper.getTopicType(topicId);
        getFactory(castKeyType(topicType)).createPartition(topicId, partitionId);
    }

    /**
     * Creates a new consumer group with the given identifier, subscribed to a
     * specified topic,
     * and initializes it with a specified rebalancing strategy.
     *
     * @param groupId     The identifier for the new consumer group.
     * @param topicId     The topic the consumer group will be subscribed to.
     * @param rebalancing The rebalancing method (e.g., "range", "roundrobin").
     * @throws IllegalArgumentException if the consumer group already exists.
     */
    public void createConsumerGroup(String groupId, String topicId, String rebalancing)
            throws IllegalArgumentException {
        if (helper.getConsumerGroup(groupId) != null) {
            throw new IllegalArgumentException("Consumer group " + groupId + " already exists.");
        }
        String type = helper.getTopicType(topicId);
        getFactory(castKeyType(type)).createConsumerGroup(groupId, topicId, rebalancing);
    }

    /**
     * Creates a new consumer within a specified consumer group.
     *
     * @param groupId    The identifier of the consumer group.
     * @param consumerId The identifier for the new consumer.
     * @throws IllegalArgumentException if the consumer already exists in the group.
     */
    public void createConsumer(String groupId, String consumerId) throws IllegalArgumentException {
        ConsumerGroup<?> group = helper.getConsumerGroup(groupId);
        if (group.containsConsumer(consumerId)) {
            throw new IllegalArgumentException("Consumer " + consumerId + " already exists in group " + groupId + ".");
        }
        String topicType = helper.getTopicType(group.getAssignedTopics().get(0).getId());
        getFactory(castKeyType(topicType)).createConsumer(groupId, consumerId);
    }

    /**
     * Creates a new producer for publishing events to a specified topic.
     *
     * @param producerId The identifier for the producer.
     * @param topicId    The topic the producer will publish to.
     * @param allocation The method of partition allocation (e.g., "random",
     *                   "manual").
     * @throws IllegalArgumentException if the producer creation fails due to a type
     *                                  mismatch.
     */
    public void createProducer(String producerId, String topicId, String allocation) throws IllegalArgumentException {
        String topicType = helper.getTopic(topicId).getType().getSimpleName().toLowerCase();
        getFactory(castKeyType(topicType)).createProducer(producerId, topicId, allocation);
    }

    /**
     * Creates an event from a specified producer to a given topic and partition.
     *
     * @param producerId  The identifier of the producer.
     * @param topicId     The topic to publish the event to.
     * @param eventId     The event identifier (e.g., filename).
     * @param partitionId The partition identifier (optional).
     * @throws IOException if event creation fails.
     */
    public void createEvent(String producerId, String topicId, JSONObject event, String partitionId)
            throws IllegalArgumentException {
        Producer<?> producer = helper.getProducer(producerId);
        Topic<?> topic = helper.getTopic(topicId);
        if (producer == null || topic == null) {
            throw new IllegalArgumentException("Producer " + producerId + " or topic " + topicId + " not found.");
        } else if (!helper.verifyProducer(producer, topic)) {
            throw new IllegalArgumentException("Producer does not have permission to produce to this topic.");
        } else if (!topic.getType().equals(producer.getType())) {
            throw new IllegalArgumentException("Producer type does not match topic type.");
        }

        synchronized (topic) {
            String topicType = topic.getType().getSimpleName().toLowerCase();
            getFactory(castKeyType(topicType)).createEvent(producerId, topicId, event, partitionId);
        }
    }

    /**
     * Deletes a consumer from the Tributary Cluster and triggers a rebalance in its
     * group.
     *
     * @param consumerId The identifier of the consumer to delete.
     */
    public void deleteConsumer(String consumerId) {
        cluster.deleteConsumer(consumerId);
    }

    /**
     * Returns a JSON representation of a specified topic, including its partitions
     * and messages.
     *
     * @param topicId The identifier of the topic to display.
     * @return A JSONObject representing the topic.
     * @throws IllegalArgumentException if the topic is not found.
     */
    public JSONObject showTopic(String topicId) {
        Topic<?> topic = cluster.getTopic(topicId);
        if (topic == null) {
            throw new IllegalArgumentException("Topic " + topicId + " not found.");
        }
        return topic.showTopic();
    }

    /**
     * Returns a JSON representation of a specified consumer group, including its
     * consumers and their assigned partitions.
     *
     * @param groupId The identifier of the consumer group to display.
     * @return A JSONObject representing the consumer group.
     * @throws IllegalArgumentException if the group is not found.
     */
    public JSONObject showGroup(String groupId) {
        ConsumerGroup<?> group = cluster.getConsumerGroup(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Group " + groupId + " not found.");
        }
        return group.showGroup();
    }

    /**
     * Consumes events from a specified partition using a consumer.
     *
     * @param consumerId     The identifier of the consumer.
     * @param partitionId    The identifier of the partition to consume from.
     * @param numberOfEvents The number of events to consume.
     * @return A JSONObject containing the consumed events.
     * @throws IllegalArgumentException if the consumer or partition is not found,
     *                                  or if access is denied.
     */
    public JSONObject consumeEvents(String consumerId, String partitionId, int numberOfEvents) {
        // Find the consumer first
        Consumer<?> consumer = helper.findConsumer(consumerId);
        if (consumer == null) {
            throw new IllegalArgumentException("Consumer " + consumerId + " not found.");
        }

        // Retrieve the partition from the consumer's assigned partitions
        Partition<?> partition = consumer.getPartition(partitionId);
        if (partition == null) {
            throw new IllegalArgumentException(
                    "Partition " + partitionId + " not found for consumer " + consumerId + ".");
        }

        Topic<?> topic = partition.getAllocatedTopic();
        if (!helper.verifyConsumer(consumer, topic)) {
            throw new IllegalArgumentException("Consumer Group of consumer " + consumerId
                    + " does not have permission to consume from topic " + topic.getId() + ".");
        }

        JSONObject data;
        if (topic.getType().equals(Integer.class)) {
            data = helper.consumeEventsGeneric(consumer, partition, Integer.class, numberOfEvents);
        } else if (topic.getType().equals(String.class)) {
            data = helper.consumeEventsGeneric(consumer, partition, String.class, numberOfEvents);
        } else {
            throw new IllegalArgumentException("Unsupported type: " + topic.getType().getSimpleName());
        }

        return data;
    }

    /**
     * Updates the rebalancing strategy of a consumer group.
     *
     * @param groupId     The identifier of the consumer group.
     * @param rebalancing The new rebalancing strategy (e.g., "range",
     *                    "roundrobin").
     */
    public void updateRebalancing(String groupId, String rebalancing) {
        ConsumerGroup<?> group = helper.getConsumerGroup(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Consumer group " + groupId + " not found.");
        }
        RebalancingStrategy<?> prevMethod = group.getRebalanceMethod();
        group.setRebalancingMethod(rebalancing);
        group.rebalance();

        RebalancingStrategy<?> currMethod = group.getRebalanceMethod();
        if (prevMethod.equals(currMethod)) {
            System.out.println("Updated the rebalancing strategy for the " + groupId
                    + " group to " + currMethod.toString() + " rebalancing");
        }
    }

    /**
     * Updates the partition offset for a consumer to allow message replay or
     * backtracking.
     *
     * @param consumerId  The identifier of the consumer.
     * @param partitionId The identifier of the partition.
     * @param offset      The offset to set for the consumer.
     */
    public void updatePartitionOffset(String consumerId, String partitionId, int offset) {
        Consumer<?> consumer = helper.findConsumer(consumerId);
        Partition<?> partition = helper.findPartition(partitionId);
        Topic<?> topic = partition.getAllocatedTopic();

        if (consumer == null || partition == null || topic == null) {
            throw new IllegalArgumentException("Consumer, partition, or topic not found.");
        }
        helper.updatePartitionOffsetGeneric(consumer, partition, offset);
    }

    /**
     * Updates the admin role for a consumer group.
     *
     * @param newGroupId The identifier of the new admin consumer group.
     * @param oldGroupId The identifier of the old admin consumer group (optional).
     * @param password   The password for verification (optional).
     */
    public void updateConsumerGroupAdmin(String newGroupId, String oldGroupId, String password)
            throws IllegalArgumentException {
        if (cluster.getTokenManager() == null) {
            cluster.setTokenManager(new TokenManager(password));
        }
        TokenManager tm = cluster.getTokenManager();
        ConsumerGroup<?> oldGroup = helper.getConsumerGroup(oldGroupId);

        if (oldGroup == null && tm.getAdminConsToken() != null) {
            throw new IllegalArgumentException("Admin token exists but old Admin could not be identified.");
        } else if (oldGroup != null && tm.getAdminConsToken() == null) {
            throw new IllegalArgumentException("Old admin token not found.");
        } else if (oldGroup != null && tm.getAdminConsToken() != null) {
            oldGroup.clearAssignments();
            oldGroup.setToken(null);
            oldGroup.rebalance();

            String token = tm.getAdminConsToken();
            if (!TokenManager.validateToken(token, oldGroup.getId(), oldGroup.getCreatedTime(), password)) {
                throw new IllegalArgumentException("Incorrect token for old Consumer Group Admin.");
            }
        }

        ConsumerGroup<?> newGroup = helper.getConsumerGroup(newGroupId);
        if (newGroup == null) {
            throw new IllegalArgumentException("New Consumer Group Admin " + newGroupId + " not found.");
        }

        String token = TokenManager.generateToken(newGroup.getId(), newGroup.getCreatedTime());
        tm.setAdminConsToken(token);
        newGroup.setToken(token);
        helper.assignTopicGeneric(newGroup);
        newGroup.rebalance();
        newGroup.showTopics();
        newGroup.showGroup();
    }

    /**
     * Updates the admin role for a producer.
     *
     * @param newProdId The identifier of the new admin producer.
     * @param oldProdId The identifier of the old admin producer (optional).
     * @param password  The password for verification (optional).
     */
    public void updateProducerAdmin(String newProdId, String oldProdId, String password) {
        if (cluster.getTokenManager() == null) {
            cluster.setTokenManager(new TokenManager(password));
        }
        TokenManager tm = cluster.getTokenManager();
        Producer<?> oldProd = helper.getProducer(oldProdId);

        if (oldProd != null && tm.getAdminProdToken() != null) {
            oldProd.clearAssignments();
            oldProd.setToken(null);

            String token = tm.getAdminProdToken();
            if (!TokenManager.validateToken(token, oldProd.getId(), oldProd.getCreatedTime(), password)) {
                throw new IllegalArgumentException("Invalid token for old Producer Admin.");
            }
        } else if (oldProd != null) {
            throw new IllegalArgumentException("Old admin token not found.");
        } else if (tm.getAdminProdToken() != null) {
            throw new IllegalArgumentException("Admin token exists but old Admin could not be identified.");
        }

        Producer<?> newProd = helper.getProducer(newProdId);
        if (newProd == null) {
            throw new IllegalArgumentException("New Producer Admin " + newProdId + " not found.");
        }

        String token = TokenManager.generateToken(newProd.getId(), newProd.getCreatedTime());
        tm.setAdminProdToken(token);
        newProd.setToken(token);
        helper.assignTopicGeneric(newProd);
        newProd.showTopics();
    }

    /**
     * Produces a series of events in parallel.
     *
     * @param producers  An array of producer IDs.
     * @param topics     An array of topic IDs.
     * @param events     A JSONArray where each element is an event represented as a
     *                   JSONObject.
     * @param partitions An array of partition IDs.
     * @throws IllegalArgumentException if input array lengths do not match or if
     *                                  validation fails.
     */
    public void parallelProduce(ParallelEventRequest dto) throws IllegalArgumentException, RuntimeException {
        List<String> producers = dto.getProducers();
        List<String> topics = dto.getTopics();
        JSONArray events = dto.getEvents();
        List<String> partitions = dto.getPartitions();

        if (producers.size() != topics.size() || producers.size() != events.length()
                || producers.size() != partitions.size()) {
            throw new IllegalArgumentException("All input arrays must have the same length.");
        }

        ExecutorService executorService = Executors.newCachedThreadPool();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < producers.size(); i++) {
            final String producerId = producers.get(i);
            final String topicId = topics.get(i);
            final JSONObject eventObj = events.getJSONObject(i);
            final String partitionId = partitions.get(i);

            Producer<?> producer = helper.getProducer(producerId);
            Topic<?> topic = helper.getTopic(topicId);
            if (producer == null || topic == null) {
                throw new IllegalArgumentException("Producer " + producerId + " or topic " + topicId + " not found.");
            } else if (!topic.getType().equals(producer.getType())) {
                throw new IllegalArgumentException(
                        "Producer " + producerId + " type does not match Topic " + topicId + " type.");
            }

            // For manual producers, ensure a valid partition is provided.
            if (producer instanceof ManualProducer) {
                if (partitionId == null || partitionId.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Manual producer " + producerId + " requires a valid partition id.");
                }
                if (helper.findPartition(partitionId) == null) {
                    throw new IllegalArgumentException("Partition " + partitionId + " not found.");
                }
            }

            // Submit a task to produce the event.
            Future<?> future = executorService.submit(() -> {
                // Directly use the event object's string representation.
                createEvent(producerId, topicId, eventObj, partitionId);
            });
            futures.add(future);
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel produce interrupted", e);
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Future execution interrupted", e);
            }
        }
    }

    /**
     * Consumes events in parallel from multiple partitions as specified by the
     * input arrays.
     * Returns a JSONObject with an "events" key mapping to a JSONArray containing
     * the consumption results for each consumer (using the consumer's id as the
     * key).
     *
     * @param consumerIds  An array of consumer IDs.
     * @param partitionIds An array of partition IDs.
     * @param numEvents    An array of numbers specifying how many events to consume
     *                     for each consumer.
     * @return A JSONObject structured as:
     *         { "events": [ { "consumerId1": [ ... ] }, { "consumerId2": [ ... ] },
     *         ... ] }
     * @throws IllegalArgumentException if the input arrays do not have the same
     *                                  length or if any required entity is not
     *                                  found.
     */
    public JSONObject parallelConsume(ParallelEventRequest dto) {
        List<String> consumerIds = dto.getConsumers();
        List<String> partitionIds = dto.getPartitions();
        List<Integer> numEvents = dto.getNumEvents();

        if (consumerIds.size() != partitionIds.size() || consumerIds.size() != numEvents.size()) {
            throw new IllegalArgumentException("All input arrays must have the same length.");
        }

        ExecutorService executorService = Executors.newFixedThreadPool(consumerIds.size());
        List<Future<JSONObject>> futures = new ArrayList<>();

        for (int i = 0; i < consumerIds.size(); i++) {
            final String consumerId = consumerIds.get(i);
            final String partitionId = partitionIds.get(i);
            final int numberOfEvents = numEvents.get(i);

            Partition<?> partition = helper.findPartition(partitionId);
            Consumer<?> consumer = helper.findConsumer(consumerId);
            if (consumer == null || partition == null) {
                throw new IllegalArgumentException(
                        "Consumer " + consumerId + " or partition " + partitionId + " not found.");
            }
            Topic<?> topic = partition.getAllocatedTopic();
            if (!helper.verifyConsumer(consumer, topic)) {
                throw new IllegalArgumentException("Consumer group of consumer " + consumerId
                        + " does not have permission to consume from topic " + topic.getId() + ".");
            }

            Future<JSONObject> future = executorService.submit(() -> {
                return consumeEvents(consumer.getId(), partition.getId(), numberOfEvents);
            });
            futures.add(future);
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel consume interrupted: " + e.getMessage(), e);
        }

        JSONArray eventsArray = new JSONArray();
        for (Future<JSONObject> future : futures) {
            try {
                JSONObject consumerEvents = future.get();
                eventsArray.put(consumerEvents);
            } catch (Exception e) {
                throw new RuntimeException("Error retrieving future result: " + e.getMessage(), e);
            }
        }

        JSONObject result = new JSONObject();
        result.put("events", eventsArray);
        return result;
    }

    public static void main(String[] args) {
        TributaryController controller = new TributaryController();

        // Create a topic "banana" with type "string"
        controller.createTopic("banana", "string");

        // Create partitions for the "banana" topic
        controller.createPartition("banana", "bananaCookingMethod1");
        controller.createPartition("banana", "bananaCookingMethod2");
        controller.createPartition("banana", "bananaCookingMethod3");
        controller.createPartition("banana", "bananaCookingStyle4");

        // Create a consumer group "bananaChefs" for the "banana" topic
        controller.createConsumerGroup("bananaChefs", "banana", "roundrobin");

        // Create consumers within the "bananaChefs" group
        controller.createConsumer("bananaChefs", "chef1");
        controller.createConsumer("bananaChefs", "chef2");
        controller.createConsumer("bananaChefs", "chef3");
        controller.createConsumer("bananaChefs", "chef4");

        // Create producers for the "banana" topic
        controller.createProducer("bananaBoiler", "banana", "manual");
        controller.createProducer("bananaFrier", "banana", "random");

        try {
            JSONObject blendBanana = new JSONObject(
                    Files.readString(Paths.get("messageConfigs/blendBanana.json")));
            JSONObject boilBanana = new JSONObject(
                    Files.readString(Paths.get("messageConfigs/boilBanana.json")));
            JSONObject freezeBanana = new JSONObject(
                    Files.readString(Paths.get("messageConfigs/freezeBanana.json")));
            JSONObject fryBanana = new JSONObject(
                    Files.readString(Paths.get("messageConfigs/fryBanana.json")));
            JSONObject grillBanana = new JSONObject(
                    Files.readString(Paths.get("messageConfigs/grillBanana.json")));
            JSONObject roastBanana = new JSONObject(
                    Files.readString(Paths.get("messageConfigs/roastBanana.json")));
            JSONObject steamBanana = new JSONObject(
                    Files.readString(Paths.get("messageConfigs/steamBanana.json")));

            // Create events using the JSON file names provided
            controller.createEvent("bananaBoiler", "banana", blendBanana, "bananaCookingMethod1");
            controller.createEvent("bananaBoiler", "banana", boilBanana,
                    "bananaCookingStyle1");
            controller.createEvent("bananaBoiler", "banana", freezeBanana,
                    "bananaCookingMethod1");
            controller.createEvent("bananaFrier", "banana", fryBanana, null);
            controller.createEvent("bananaFrier", "banana", grillBanana, null);
            controller.createEvent("bananaFrier", "banana", roastBanana, null);
            controller.createEvent("bananaBoiler", "banana", steamBanana,
                    "bananaCookingMethod1");
        } catch (IOException e) {
            System.out.println(e);
            e.printStackTrace();
        }

        // Retrieve JSON representations of the topic and consumer group
        JSONObject consume1 = controller.consumeEvents("chef1", "bananaCookingMethod1", 1);
        JSONObject consumeMultiple = controller.consumeEvents("chef1", "bananaCookingMethod1", 4);

        // Print the JSON outputs
        System.out.println(consume1.toString(2));
        System.out.println(consumeMultiple.toString(2));
    }
}
